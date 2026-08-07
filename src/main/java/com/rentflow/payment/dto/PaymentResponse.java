package com.rentflow.payment.dto;

import com.rentflow.payment.Payment;
import com.rentflow.payment.PaymentStatus;
import com.rentflow.payment.PaymentType;

import java.math.BigDecimal;

/**
 * One charge, as the client sees it.
 *
 * {@code clientSecret} is the only field not read from the row — it comes straight from
 * the gateway and is never persisted, because it is a bearer credential for this one
 * intent. Null means there is nothing left to pay here (the charge already settled).
 */
public record PaymentResponse(
        Long id,
        Long bookingId,
        PaymentType type,
        BigDecimal amount,
        PaymentStatus status,
        String gatewayRef,
        String clientSecret,
        String failureReason
) {
    public static PaymentResponse from(Payment payment, String clientSecret) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBookingId(),
                payment.getType(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getGatewayRef(),
                clientSecret,
                payment.getFailureReason()
        );
    }
}
