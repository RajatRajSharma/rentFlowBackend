package com.rentflow.event;

import com.rentflow.payment.PaymentType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A charge cleared. Carries the amount rather than just ids, so a consumer can write an
 * email without calling back into us and racing the transaction that produced this.
 */
public record PaymentSucceeded(
        Long paymentId,
        Long bookingId,
        PaymentType paymentType,
        BigDecimal amount,
        Instant occurredAt
) implements DomainEvent {

    public PaymentSucceeded(Long paymentId, Long bookingId, PaymentType paymentType, BigDecimal amount) {
        this(paymentId, bookingId, paymentType, amount, Instant.now());
    }

    @Override
    public String type() {
        return "payment.succeeded";
    }
}
