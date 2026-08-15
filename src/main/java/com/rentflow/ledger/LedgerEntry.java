package com.rentflow.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of the double-entry ledger. Maps to `ledger_entries` (V4).
 *
 * <h3>Append-only, and the class says so</h3>
 * Note what this entity does NOT have: setters, an {@code updatedAt}, or a {@code @Version}.
 * It deliberately does not extend {@link com.rentflow.common.audit.Auditable} like every
 * other entity here, because an accounting record you can edit is not an accounting record.
 * A mistake is corrected by posting a new balancing pair, never by rewriting history —
 * which is also why there is nothing to contend on and so no optimistic lock to take.
 *
 * <h3>One-sided by construction</h3>
 * A line is either a debit or a credit, never both and never neither. The two factory
 * methods are the only way to make one, so the illegal shapes aren't expressible — and the
 * database enforces the same rule again with a CHECK, for anything that reaches it by
 * another route.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    /** Null for settlement movements that have no gateway payment behind them. */
    @Column(name = "payment_id", updatable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LedgerAccount account;

    @Column(nullable = false, updatable = false)
    private BigDecimal debit;

    @Column(nullable = false, updatable = false)
    private BigDecimal credit;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    private LedgerEntry(Long bookingId, Long paymentId, LedgerAccount account,
                        BigDecimal debit, BigDecimal credit) {
        this.bookingId = bookingId;
        this.paymentId = paymentId;
        this.account = account;
        this.debit = debit;
        this.credit = credit;
    }

    /** Value flowing INTO this account. */
    public static LedgerEntry debit(Long bookingId, Long paymentId, LedgerAccount account, BigDecimal amount) {
        return new LedgerEntry(bookingId, paymentId, account, amount, BigDecimal.ZERO);
    }

    /** Value flowing OUT of this account. */
    public static LedgerEntry credit(Long bookingId, Long paymentId, LedgerAccount account, BigDecimal amount) {
        return new LedgerEntry(bookingId, paymentId, account, BigDecimal.ZERO, amount);
    }

    public Long getId() {
        return id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public BigDecimal getDebit() {
        return debit;
    }

    public BigDecimal getCredit() {
        return credit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
