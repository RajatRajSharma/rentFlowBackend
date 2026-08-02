package com.rentflow.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    // Unparseable parameter, e.g. ?from=not-a-date -> 400 instead of a 500
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue(), null);
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
