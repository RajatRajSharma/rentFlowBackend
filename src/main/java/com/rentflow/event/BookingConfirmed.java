package com.rentflow.event;

import java.time.Instant;

/**
 * Every charge cleared and the item is reserved. The event both parties get emailed about
 * (Day 17) and the one the renter's browser is waiting on (Day 19).
 */
public record BookingConfirmed(
        Long bookingId,
        Long itemId,
        Long renterId,
        Instant occurredAt
) implements DomainEvent {

    public static BookingConfirmed of(Long bookingId, Long itemId, Long renterId) {
        return new BookingConfirmed(bookingId, itemId, renterId, Instant.now());
    }

    @Override
    public String type() {
        return "booking.confirmed";
    }
}
