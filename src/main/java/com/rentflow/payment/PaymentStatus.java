package com.rentflow.payment;

/**
 * Where a {@link Payment} is in its life.
 *
 * Deliberately only three states, and deliberately NOT a state machine like
 * {@link com.rentflow.booking.BookingStateMachine}: a payment's path is linear
 * (PENDING → SUCCEEDED or PENDING → FAILED) with no branching to police.
 *
 * The important property is that PENDING is not a failure. A gateway that times out
 * leaves us here, and assuming "no answer means no charge" is how real systems end up
 * charging a customer whose booking they also cancelled. Only the gateway gets to say
 * SUCCEEDED or FAILED — via the webhook (Day 13) or the reconciliation sweep (Day 18).
 */
public enum PaymentStatus {

    /** Intent created; the gateway has not told us the outcome yet. */
    PENDING,

    /** The gateway confirmed the money moved. */
    SUCCEEDED,

    /** The gateway declined it. See {@code failureReason}. */
    FAILED;

    /** True once the gateway has spoken and the row will not change again. */
    public boolean isSettled() {
        return this == SUCCEEDED || this == FAILED;
    }
}
