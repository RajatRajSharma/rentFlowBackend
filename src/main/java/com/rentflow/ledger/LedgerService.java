package com.rentflow.ledger;

import com.rentflow.common.exception.UnbalancedLedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * The double-entry ledger: every money movement recorded as two balanced halves.
 *
 * <h3>Why bother, in a project with one database</h3>
 * A {@code payments} table tells you what you <em>charged</em>. It cannot tell you what you
 * <em>owe</em>. After a rental completes, ₹5000 of deposit might be partly the owner's
 * (damage) and partly the renter's (refund), and a single amount column has nowhere to put
 * that. Double-entry does, and it comes with a property no single-column design has: the
 * books can be <em>proved</em> correct at any moment by summing both sides.
 *
 * <h3>Two rules, both enforced here</h3>
 * <ol>
 *   <li><b>Nothing unbalanced is ever written.</b> {@link #post} validates before it saves,
 *       so an unbalanced ledger isn't a bug you find later — it's a transaction that never
 *       committed.</li>
 *   <li><b>Nothing is written outside its cause's transaction.</b> Hence MANDATORY
 *       propagation: a ledger entry that commits while the payment update rolls back is
 *       exactly the corruption this class exists to prevent, so calling it without an
 *       enclosing transaction fails loudly rather than quietly doing the wrong thing.</li>
 * </ol>
 *
 * This mirrors {@code ItemService.getForUpdate}, which uses MANDATORY for the same reason:
 * some operations are only correct inside someone else's transaction, and the framework
 * should say so rather than trusting everyone to remember.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerRepository ledgerRepository;

    public LedgerService(LedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * Post a set of lines as one balanced movement.
     *
     * @throws UnbalancedLedgerException if the debits and credits don't agree, or the set is
     *         empty. Deliberately a hard failure: the caller's transaction rolls back and no
     *         half-movement survives.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> post(List<LedgerEntry> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new UnbalancedLedgerException("A ledger movement must have at least two lines");
        }

        BigDecimal debits = lines.stream()
                .map(LedgerEntry::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = lines.stream()
                .map(LedgerEntry::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);

        // compareTo, not equals — BigDecimal.equals compares scale, so 2000.00 and 2000.000
        // would read as different amounts and reject a perfectly balanced movement.
        if (debits.compareTo(credits) != 0) {
            throw new UnbalancedLedgerException(
                    "Debits (" + debits + ") do not equal credits (" + credits + ")");
        }

        log.debug("Posting {} ledger lines totalling {}", lines.size(), debits);
        return ledgerRepository.saveAll(lines);
    }

    /** Do this booking's books balance? Backs the assertion in tests and, later, admin tooling. */
    @Transactional(readOnly = true)
    public LedgerBalance balanceFor(Long bookingId) {
        return ledgerRepository.balanceFor(bookingId);
    }

    /** Every line posted against a booking, oldest first. */
    @Transactional(readOnly = true)
    public List<LedgerEntry> entriesFor(Long bookingId) {
        return ledgerRepository.findByBookingIdOrderByIdAsc(bookingId);
    }

    /** True if this payment has already been posted — the guard against double-posting. */
    @Transactional(readOnly = true)
    public boolean alreadyPostedFor(Long paymentId) {
        return ledgerRepository.existsByPaymentId(paymentId);
    }
}
