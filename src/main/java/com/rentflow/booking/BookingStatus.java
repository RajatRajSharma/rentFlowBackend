package com.rentflow.booking;

import java.util.Set;

/**
 * The states a booking can be in. Legal moves between them live in
 * {@link BookingStateMachine} — this enum only names the states.
 *
 * <pre>
 * PENDING_PAYMENT ─payment ok─▶ CONFIRMED ─start date─▶ ACTIVE ─returned─▶ RETURNED ─settled─▶ CLOSED
 *        │                          │                                          │
 *        ├─fail/timeout─▶ PAYMENT_FAILED                                        └─damage─▶ DISPUTED
 *        └─cancel──────▶ CANCELLED ◀─cancel (per rules)─┘
 * </pre>
 */
public enum BookingStatus {

    /** Created, dates held, waiting for the renter to pay. */
    PENDING_PAYMENT,

    /** Payment succeeded. The item is reserved for these dates. */
    CONFIRMED,

    /** Start date reached — the renter has the item. */
    ACTIVE,

    /** Item handed back; deposit not settled yet. */
    RETURNED,

    /** Deposit settled and paid out. Terminal. */
    CLOSED,

    /** Damage claimed on return, awaiting resolution. */
    DISPUTED,

    /** Payment failed or timed out. Terminal — the dates are released. */
    PAYMENT_FAILED,

    /** Cancelled by the renter. Terminal — the dates are released. */
    CANCELLED;

    /**
     * The statuses that actually occupy an item's calendar. Only these count when we ask
     * "are these dates free?", and only these are covered by the DB exclusion constraint —
     * the two lists must stay in sync with V3__bookings_and_exclusion.sql.
     */
    public static final Set<BookingStatus> BLOCKING = Set.of(PENDING_PAYMENT, CONFIRMED, ACTIVE);

    /** True if a booking in this state holds its dates against everyone else. */
    public boolean blocksDates() {
        return BLOCKING.contains(this);
    }

    /** True if nothing can follow this state. */
    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED || this == PAYMENT_FAILED;
    }
}
