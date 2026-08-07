package com.rentflow.payment.dto;

import com.rentflow.booking.BookingStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * The answer to POST /bookings/{id}/pay: everything the client must settle, itemised.
 *
 * Fee and deposit are returned as separate lines rather than one total because they are
 * genuinely different money — the fee is earned, the deposit is only held — and the
 * renter is entitled to see which is which before paying.
 *
 * {@code bookingStatus} is echoed so a client replaying a request can tell whether the
 * booking has meanwhile been CONFIRMED, without a second round trip.
 */
public record PaymentIntentResponse(
        Long bookingId,
        BookingStatus bookingStatus,
        String currency,
        BigDecimal totalCharged,
        List<PaymentResponse> payments
) {
}
