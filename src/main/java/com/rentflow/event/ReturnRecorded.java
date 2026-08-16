package com.rentflow.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The item came back and the deposit has been split. Published by settlement, which is what
 * decides how much is a refund and how much is a damage claim.
 */
public record ReturnRecorded(
        Long bookingId,
        Long itemId,
        boolean damaged,
        BigDecimal depositDeducted,
        BigDecimal refundAmount,
        Instant occurredAt
) implements DomainEvent {

    public static ReturnRecorded of(Long bookingId, Long itemId, boolean damaged,
                                    BigDecimal depositDeducted, BigDecimal refundAmount) {
        return new ReturnRecorded(bookingId, itemId, damaged, depositDeducted, refundAmount, Instant.now());
    }

    @Override
    public String type() {
        return "return.recorded";
    }
}
