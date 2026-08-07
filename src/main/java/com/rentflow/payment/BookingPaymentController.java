package com.rentflow.payment;

import com.rentflow.payment.dto.PaymentIntentResponse;
import com.rentflow.payment.dto.PaymentResponse;
import com.rentflow.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Payment endpoints hanging off a booking URL.
 *
 * In the payment package, not the booking one, for the same reason
 * {@code ItemBookingController} lives under booking: these are questions about payments
 * that happen to be addressed by booking id. Payment already depends on booking; putting
 * them the other way round would make the dependency circular.
 *
 * Note there is no request body. Amounts come from the booking — the server decides what
 * you owe, never the client — and identity comes from the JWT. The only thing the caller
 * contributes is the idempotency key, and that belongs in a header because it describes
 * the request rather than the resource.
 */
@RestController
public class BookingPaymentController {

    private final PaymentService paymentService;

    public BookingPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Create the payment intents (fee + deposit) for a booking you rented.
     *
     * Returns 200, not 201: a retry with the same key is the same request and must give
     * the same answer, so this cannot honestly claim to have created something each time.
     *
     * The Idempotency-Key header is REQUIRED — a missing one is a 400. Generating a key
     * server-side would defeat the point, since a retry would generate a different one and
     * charge twice; and silently accepting the risk on a money endpoint is not a default
     * worth having.
     */
    @PostMapping("/bookings/{id}/pay")
    public PaymentIntentResponse pay(@PathVariable Long id,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @AuthenticationPrincipal AuthenticatedUser user) {
        return paymentService.pay(id, user.id(), idempotencyKey);
    }

    /** What has been charged against this booking. Renter only. */
    @GetMapping("/bookings/{id}/payments")
    public List<PaymentResponse> list(@PathVariable Long id,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return paymentService.findForBooking(id, user.id());
    }
}
