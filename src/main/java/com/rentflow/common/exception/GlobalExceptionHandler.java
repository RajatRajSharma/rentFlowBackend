package com.rentflow.common.exception;

import com.rentflow.payment.gateway.PaymentGatewayException;
import com.rentflow.payment.gateway.WebhookSignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * One place that turns exceptions into clean HTTP responses — so controllers stay
 * free of try/catch soup. Add an @ExceptionHandler method per exception type.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Bad email/password at login -> 401
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), null);
    }

    // Acting on someone else's resource -> 403
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException ex) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), null);
    }

    // Missing resource -> 404
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    // Duplicate email at register -> 409
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(DuplicateEmailException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    // Dates already taken, or item not bookable -> 409
    @ExceptionHandler(BookingConflictException.class)
    public ResponseEntity<ApiError> handleBookingConflict(BookingConflictException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    // Illegal booking status change -> 409 (valid request, wrong current state)
    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<ApiError> handleIllegalTransition(IllegalTransitionException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    // Nonsensical date range -> 400
    @ExceptionHandler(InvalidDateRangeException.class)
    public ResponseEntity<ApiError> handleInvalidDateRange(InvalidDateRangeException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    // Semantically bad request that bean validation can't express -> 400
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    // @Valid failures -> 400, with a field -> message map
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    // Missing query parameter, e.g. /availability without ?from= -> 400 in our own shape
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, "Missing required parameter: " + ex.getParameterName(), null);
    }

    // Missing required header, e.g. /pay without Idempotency-Key -> 400, naming the header
    // so the client knows what to add rather than guessing at a generic "bad request".
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
        return build(HttpStatus.BAD_REQUEST, "Missing required header: " + ex.getHeaderName(), null);
    }

    // Unparseable parameter, e.g. ?from=not-a-date -> 400 instead of a 500
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue(), null);
    }

    // Malformed or missing JSON body -> 400, without echoing the parser's internals
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or missing JSON request body", null);
    }

    // Lock contention -> 409; the caller can reasonably retry
    @ExceptionHandler(LockAcquisitionException.class)
    public ResponseEntity<ApiError> handleLockAcquisition(LockAcquisitionException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    // Two transactions updated the same row (@Version) -> 409, not a 500
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return build(HttpStatus.CONFLICT,
                "That record was modified by someone else — reload it and try again", null);
    }

    /**
     * A webhook that isn't genuinely from our gateway -> 400.
     *
     * The status is an instruction, not decoration: on a 4xx the gateway stops redelivering,
     * which is right for a payload that will never become valid. A 401/403 would be the wrong
     * story (this is not a user being denied) and a 5xx would have it retry for days.
     *
     * The message stays vague on purpose. "Signature mismatch" versus "timestamp too old"
     * tells someone probing the endpoint exactly which half of the scheme they got wrong;
     * the detail goes to our logs instead.
     */
    @ExceptionHandler(WebhookSignatureException.class)
    public ResponseEntity<ApiError> handleWebhookSignature(WebhookSignatureException ex) {
        log.warn("Rejected webhook: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Webhook could not be verified", null);
    }

    /**
     * Our own accounting produced an unbalanced movement -> 500, and the transaction rolls
     * back. Never the caller's fault and never fixed by retrying: it means our code is wrong.
     * Logged at error because a quietly-swallowed version of this is how books stop balancing
     * without anyone noticing.
     */
    @ExceptionHandler(UnbalancedLedgerException.class)
    public ResponseEntity<ApiError> handleUnbalancedLedger(UnbalancedLedgerException ex) {
        log.error("LEDGER WOULD NOT BALANCE — transaction rolled back", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side", null);
    }

    /**
     * The payment provider rejected us or never answered -> 502 Bad Gateway.
     *
     * Not a 500: the client's request was fine, our downstream wasn't, and blaming
     * ourselves would send them off to fix a request that has nothing wrong with it.
     * Not a 4xx either: there is nothing for them to correct. 502 says "retry later",
     * which is the only useful thing we can tell them.
     */
    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ApiError> handlePaymentGateway(PaymentGatewayException ex) {
        log.warn("Payment gateway failure", ex);
        return build(HttpStatus.BAD_GATEWAY,
                "The payment provider is unavailable — your booking is unchanged, please retry", null);
    }

    // Any constraint the service didn't already translate -> 409 rather than a leaked 500
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Unhandled data integrity violation", ex);
        return build(HttpStatus.CONFLICT, "The request conflicts with existing data", null);
    }

    /**
     * Last resort. Without this, an unexpected exception leaks Spring's default error body —
     * and in some setups a stack trace. Log the detail for us, return something bland to them.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side", null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, Map<String, String> fieldErrors) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                fieldErrors
        );
        return ResponseEntity.status(status).body(body);
    }
}
