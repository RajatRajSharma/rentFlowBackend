package com.rentflow.event;

import java.time.Instant;

/**
 * The item came back. Defined now and published by settlement (Day 18), which is what
 * decides how much of the deposit is a refund and how much is a damage claim.
 */
public record ReturnRecorded(
        Long bookingId,
        Long itemId,
        Instant occurredAt
) implements DomainEvent {

    public ReturnRecorded(Long bookingId, Long itemId) {
        this(bookingId, itemId, Instant.now());
    }

    @Override
    public String type() {
        return "return.recorded";
    }
}
