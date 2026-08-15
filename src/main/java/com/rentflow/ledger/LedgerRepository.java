package com.rentflow.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Data access for ledger entries. Read and append only — there is no update path, by design.
 */
public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    /** Every line for a booking, in the order they were posted. */
    List<LedgerEntry> findByBookingIdOrderByIdAsc(Long bookingId);

    /** Whether we've already posted entries for a payment — the ledger's own dedupe check. */
    boolean existsByPaymentId(Long paymentId);

    /**
     * The proof query: total debits and total credits for a booking.
     *
     * If these two numbers ever differ, money has been invented or destroyed somewhere.
     * A constructor expression rather than an {@code Object[]} projection, so the result is
     * typed at compile time instead of being a pair of positional casts. COALESCE so a
     * booking with no entries yet returns (0, 0) rather than (null, null).
     */
    @Query("""
            SELECT new com.rentflow.ledger.LedgerBalance(
                       COALESCE(SUM(e.debit), 0), COALESCE(SUM(e.credit), 0))
            FROM LedgerEntry e
            WHERE e.bookingId = :bookingId
            """)
    LedgerBalance balanceFor(@Param("bookingId") Long bookingId);
}
