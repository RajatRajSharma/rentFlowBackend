package com.rentflow.event;

import java.time.Instant;

/**
 * Something that happened, stated as a fact — past tense, no instructions attached. Sealed,
 * so everything this system announces is one readable list.
 */
public sealed interface DomainEvent permits BookingConfirmed, PaymentSucceeded, ReturnRecorded {

    /** Dotted name, used as the routing key when Day 17 puts RabbitMQ behind this. */
    String type();

    /** When the fact became true — not when it was delivered. */
    Instant occurredAt();

    /** Every event in this system is about a booking; carrying it makes routing trivial. */
    Long bookingId();
}
