package com.rentflow.booking;

import com.rentflow.common.exception.IllegalTransitionException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The one place that decides which booking status changes are legal.
 *
 * Why a state machine instead of {@code if (status == X)} checks scattered across services:
 * illegal states become unreachable, the rules are readable as a single table, and there is
 * exactly one thing to test and one thing to change when the lifecycle grows.
 *
 * Anything not in the table below is rejected — the default is "no".
 */
@Component
public class BookingStateMachine {

    private static final Map<BookingStatus, Set<BookingStatus>> LEGAL_TRANSITIONS =
            new EnumMap<>(BookingStatus.class);

    static {
        // Waiting on money: it either clears, fails, or the renter walks away.
        LEGAL_TRANSITIONS.put(BookingStatus.PENDING_PAYMENT,
                Set.of(BookingStatus.CONFIRMED, BookingStatus.PAYMENT_FAILED, BookingStatus.CANCELLED));

        // Paid and reserved: it starts, or is cancelled under the cancellation rules.
        LEGAL_TRANSITIONS.put(BookingStatus.CONFIRMED,
                Set.of(BookingStatus.ACTIVE, BookingStatus.CANCELLED));

        // The renter physically has the item — the only way out is giving it back.
        // Deliberately NOT cancellable: money and possession have already moved.
        LEGAL_TRANSITIONS.put(BookingStatus.ACTIVE,
                Set.of(BookingStatus.RETURNED));

        // Handed back: settle the deposit, or raise a damage claim.
        LEGAL_TRANSITIONS.put(BookingStatus.RETURNED,
                Set.of(BookingStatus.CLOSED, BookingStatus.DISPUTED));

        // A dispute must be able to end, otherwise the deposit is stuck forever.
        LEGAL_TRANSITIONS.put(BookingStatus.DISPUTED,
                Set.of(BookingStatus.CLOSED));

        // Terminal states — no way out, and the dates are free again.
        LEGAL_TRANSITIONS.put(BookingStatus.CLOSED, Set.of());
        LEGAL_TRANSITIONS.put(BookingStatus.CANCELLED, Set.of());
        LEGAL_TRANSITIONS.put(BookingStatus.PAYMENT_FAILED, Set.of());
    }

    /** Which states can be reached from here. Empty for terminal states. */
    public Set<BookingStatus> nextStates(BookingStatus from) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of());
    }

    /** Non-throwing check — use when you want to branch rather than fail. */
    public boolean canTransition(BookingStatus from, BookingStatus to) {
        return from != null && to != null && nextStates(from).contains(to);
    }

    /**
     * Enforce a transition. Throws {@link IllegalTransitionException} (→ 409 Conflict) if the
     * move isn't allowed, so callers never have to remember the rules themselves.
     */
    public void requireTransition(BookingStatus from, BookingStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }
}
