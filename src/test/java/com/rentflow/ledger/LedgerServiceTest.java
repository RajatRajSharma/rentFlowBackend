package com.rentflow.ledger;

import com.rentflow.common.exception.UnbalancedLedgerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ledger's one invariant, tested without a database: nothing unbalanced is ever written.
 *
 * Worth having as a unit test rather than only an integration one, because this is the rule
 * the whole double-entry design exists to enforce — it should be impossible to break it
 * without a red test, and it should take milliseconds to find out.
 */
@DisplayName("LedgerService")
class LedgerServiceTest {

    private static final Long BOOKING = 1L;
    private static final Long PAYMENT = 9L;

    private final LedgerRepository repository = mock(LedgerRepository.class);
    private final LedgerService service = new LedgerService(repository);

    @Nested
    @DisplayName("rejects movements that would unbalance the books")
    class Rejects {

        @Test
        @DisplayName("debits greater than credits")
        void debitsExceedCredits() {
            List<LedgerEntry> lines = List.of(
                    LedgerEntry.debit(BOOKING, PAYMENT, LedgerAccount.RENTER_CASH, new BigDecimal("2000.00")),
                    LedgerEntry.credit(BOOKING, PAYMENT, LedgerAccount.OWNER_PAYABLE, new BigDecimal("1500.00")));

            assertThatThrownBy(() -> service.post(lines))
                    .isInstanceOf(UnbalancedLedgerException.class)
                    .hasMessageContaining("2000")
                    .hasMessageContaining("1500");

            // The point of failing before saving: no half-movement survives.
            verify(repository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("credits greater than debits")
        void creditsExceedDebits() {
            List<LedgerEntry> lines = List.of(
                    LedgerEntry.debit(BOOKING, PAYMENT, LedgerAccount.RENTER_CASH, new BigDecimal("100.00")),
                    LedgerEntry.credit(BOOKING, PAYMENT, LedgerAccount.OWNER_PAYABLE, new BigDecimal("900.00")));

            assertThatThrownBy(() -> service.post(lines)).isInstanceOf(UnbalancedLedgerException.class);
            verify(repository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("an empty movement")
        void emptyMovement() {
            assertThatThrownBy(() -> service.post(List.of())).isInstanceOf(UnbalancedLedgerException.class);
            assertThatThrownBy(() -> service.post(null)).isInstanceOf(UnbalancedLedgerException.class);
            verify(repository, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("accepts balanced movements")
    class Accepts {

        @Test
        @DisplayName("a simple two-line pair")
        void simplePair() {
            List<LedgerEntry> lines = List.of(
                    LedgerEntry.debit(BOOKING, PAYMENT, LedgerAccount.RENTER_CASH, new BigDecimal("2000.00")),
                    LedgerEntry.credit(BOOKING, PAYMENT, LedgerAccount.OWNER_PAYABLE, new BigDecimal("2000.00")));
            when(repository.saveAll(anyList())).thenReturn(lines);

            assertThatCode(() -> service.post(lines)).doesNotThrowAnyException();
            verify(repository).saveAll(lines);
        }

        @Test
        @DisplayName("one debit split across several credits — the damage-claim shape")
        void splitCredits() {
            // The README's worked example: DEPOSIT_HELD 5000 out, 1500 to the owner for
            // damage and 3500 back to the renter. Three lines, still balanced.
            List<LedgerEntry> lines = List.of(
                    LedgerEntry.debit(BOOKING, null, LedgerAccount.DEPOSIT_HELD, new BigDecimal("5000.00")),
                    LedgerEntry.credit(BOOKING, null, LedgerAccount.OWNER_PAYABLE, new BigDecimal("1500.00")),
                    LedgerEntry.credit(BOOKING, null, LedgerAccount.RENTER_REFUND, new BigDecimal("3500.00")));
            when(repository.saveAll(anyList())).thenReturn(lines);

            assertThatCode(() -> service.post(lines)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("amounts that differ only in scale — 2000.00 vs 2000.000")
        void differingScalesStillBalance() {
            // BigDecimal.equals would call these different. Using it here instead of
            // compareTo would reject a perfectly balanced movement.
            List<LedgerEntry> lines = List.of(
                    LedgerEntry.debit(BOOKING, PAYMENT, LedgerAccount.RENTER_CASH, new BigDecimal("2000.00")),
                    LedgerEntry.credit(BOOKING, PAYMENT, LedgerAccount.OWNER_PAYABLE, new BigDecimal("2000.000")));
            when(repository.saveAll(anyList())).thenReturn(lines);

            assertThatCode(() -> service.post(lines)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("LedgerBalance")
    class Balance {

        @Test
        @DisplayName("compares by value, not by scale")
        void comparesByValue() {
            assertThat(new LedgerBalance(new BigDecimal("7000.00"), new BigDecimal("7000.000")).isBalanced())
                    .isTrue();
            assertThat(new LedgerBalance(new BigDecimal("7000.00"), new BigDecimal("6999.99")).isBalanced())
                    .isFalse();
        }

        @Test
        @DisplayName("drift is signed, so the direction of the error is visible")
        void driftIsSigned() {
            assertThat(new LedgerBalance(new BigDecimal("100"), new BigDecimal("40")).drift())
                    .isEqualByComparingTo("60");
            assertThat(new LedgerBalance(new BigDecimal("40"), new BigDecimal("100")).drift())
                    .isEqualByComparingTo("-60");
        }
    }

    @Test
    @DisplayName("every LedgerAccount is allowed by the migration's CHECK constraint")
    void enumMatchesTheDatabaseConstraint() throws IOException {
        // The enum and the CHECK in V4 are two statements of the same rule, and nothing but
        // this test stops them drifting. A new account name that the database rejects would
        // otherwise surface as a 500 the first time settlement tried to use it.
        String migration = new String(
                getClass().getResourceAsStream("/db/migration/V4__payments_ledger.sql").readAllBytes(),
                StandardCharsets.UTF_8);

        String checkClause = migration.substring(
                migration.indexOf("CHECK (account IN ("),
                migration.indexOf("))", migration.indexOf("CHECK (account IN (")));

        Set<String> declared = Set.of(LedgerAccount.values()).stream()
                .map(Enum::name).collect(Collectors.toSet());

        assertThat(declared).allSatisfy(name ->
                assertThat(checkClause)
                        .as("LedgerAccount.%s is missing from the V4 CHECK constraint", name)
                        .contains("'" + name + "'"));
    }
}
