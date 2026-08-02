package com.rentflow.booking;

import com.rentflow.common.exception.IllegalTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit tests — no Spring context, no database. The state machine is pure logic,
 * so it should be testable in milliseconds.
 */
class BookingStateMachineTest {

    private final BookingStateMachine stateMachine = new BookingStateMachine();

    @Nested
    @DisplayName("legal transitions")
    class Legal {

        @ParameterizedTest(name = "{0} -> {1} is allowed")
        @CsvSource({
                "PENDING_PAYMENT, CONFIRMED",
                "PENDING_PAYMENT, PAYMENT_FAILED",
                "PENDING_PAYMENT, CANCELLED",
                "CONFIRMED,       ACTIVE",
                "CONFIRMED,       CANCELLED",
                "ACTIVE,          RETURNED",
                "RETURNED,        CLOSED",
                "RETURNED,        DISPUTED",
                "DISPUTED,        CLOSED"
        })
        void allowsEveryTransitionInTheDesign(BookingStatus from, BookingStatus to) {
            assertThat(stateMachine.canTransition(from, to)).isTrue();
            assertThatCode(() -> stateMachine.requireTransition(from, to)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the happy path runs end to end")
        void happyPathIsWalkable() {
            BookingStatus[] path = {
                    BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED, BookingStatus.ACTIVE,
                    BookingStatus.RETURNED, BookingStatus.CLOSED
            };
            for (int i = 0; i < path.length - 1; i++) {
                assertThat(stateMachine.canTransition(path[i], path[i + 1]))
                        .as("%s -> %s", path[i], path[i + 1])
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("illegal transitions")
    class Illegal {

        @ParameterizedTest(name = "{0} -> {1} is rejected")
        @CsvSource({
                // Can't skip payment.
                "PENDING_PAYMENT, ACTIVE",
                "PENDING_PAYMENT, RETURNED",
                "PENDING_PAYMENT, CLOSED",
                // Can't go backwards.
                "CONFIRMED,       PENDING_PAYMENT",
                "ACTIVE,          CONFIRMED",
                "RETURNED,        ACTIVE",
                // Possession has already moved — cancelling is not an option.
                "ACTIVE,          CANCELLED",
                "RETURNED,        CANCELLED",
                // Terminal states are terminal.
                "CANCELLED,       CONFIRMED",
                "PAYMENT_FAILED,  CONFIRMED",
                "CLOSED,          ACTIVE"
        })
        void rejectsIllegalJumps(BookingStatus from, BookingStatus to) {
            assertThat(stateMachine.canTransition(from, to)).isFalse();
            assertThatThrownBy(() -> stateMachine.requireTransition(from, to))
                    .isInstanceOf(IllegalTransitionException.class)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
        }

        @ParameterizedTest(name = "{0} cannot transition to itself")
        @EnumSource(BookingStatus.class)
        void rejectsSelfTransitions(BookingStatus status) {
            assertThat(stateMachine.canTransition(status, status)).isFalse();
        }

        @Test
        void rejectsNulls() {
            assertThat(stateMachine.canTransition(null, BookingStatus.CONFIRMED)).isFalse();
            assertThat(stateMachine.canTransition(BookingStatus.PENDING_PAYMENT, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("terminal states")
    class Terminal {

        @ParameterizedTest
        @EnumSource(value = BookingStatus.class,
                names = {"CLOSED", "CANCELLED", "PAYMENT_FAILED"})
        void haveNoWayOut(BookingStatus status) {
            assertThat(status.isTerminal()).isTrue();
            assertThat(stateMachine.nextStates(status)).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(value = BookingStatus.class,
                names = {"PENDING_PAYMENT", "CONFIRMED", "ACTIVE", "RETURNED", "DISPUTED"})
        void nonTerminalStatesAlwaysHaveSomewhereToGo(BookingStatus status) {
            assertThat(status.isTerminal()).isFalse();
            assertThat(stateMachine.nextStates(status)).isNotEmpty();
        }

        @Test
        @DisplayName("every state is reachable or terminal — no orphans in the table")
        void everyStateIsCovered() {
            for (BookingStatus status : BookingStatus.values()) {
                assertThat(stateMachine.nextStates(status))
                        .as("%s must have an explicit entry in the transition table", status)
                        .isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("date-blocking statuses")
    class Blocking {

        @Test
        @DisplayName("only PENDING_PAYMENT, CONFIRMED and ACTIVE hold the calendar")
        void matchesTheExclusionConstraint() {
            // These three MUST match the WHERE clause of no_overlapping_active_bookings in V3.
            assertThat(BookingStatus.BLOCKING).containsExactlyInAnyOrder(
                    BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED, BookingStatus.ACTIVE);
        }

        @ParameterizedTest
        @EnumSource(value = BookingStatus.class, names = {"CANCELLED", "PAYMENT_FAILED", "CLOSED"})
        void endedBookingsReleaseTheirDates(BookingStatus status) {
            assertThat(status.blocksDates()).isFalse();
        }

        @Test
        void blockingSetAndPredicateAgree() {
            for (BookingStatus status : BookingStatus.values()) {
                assertThat(status.blocksDates()).isEqualTo(BookingStatus.BLOCKING.contains(status));
            }
        }

        @Test
        void returnedAndDisputedDoNotBlockNewBookings() {
            // The item is physically back with the owner, so it can be re-let while the
            // deposit is still being settled.
            assertThat(Set.of(BookingStatus.RETURNED, BookingStatus.DISPUTED))
                    .allSatisfy(status -> assertThat(status.blocksDates()).isFalse());
        }
    }
}
