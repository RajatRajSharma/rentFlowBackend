package com.rentflow.booking;

import com.rentflow.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A rental of one item for a date range. Maps to the `bookings` table (V3).
 *
 * Dates are INCLUSIVE on both ends — a 1st→1st booking is one billable day — which matches
 * the '[]' daterange used by the DB exclusion constraint.
 *
 * Money is snapshot at creation: {@code totalAmount} and {@code depositAmount} are copied from
 * the item, so a later price edit never changes what an existing renter owes.
 */
@Entity
@Table(name = "bookings")
public class Booking extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "renter_id", nullable = false)
    private Long renterId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "deposit_amount", nullable = false)
    private BigDecimal depositAmount;

    /** Optimistic lock: a concurrent update to the same row is rejected instead of silently overwritten. */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Booking() {
    }

    /** Every booking starts life in PENDING_PAYMENT — the only legal entry state. */
    public Booking(Long itemId, Long renterId, LocalDate startDate, LocalDate endDate,
                   BigDecimal totalAmount, BigDecimal depositAmount) {
        this.itemId = itemId;
        this.renterId = renterId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.totalAmount = totalAmount;
        this.depositAmount = depositAmount;
        this.status = BookingStatus.PENDING_PAYMENT;
    }

    public Long getId() {
        return id;
    }

    public Long getItemId() {
        return itemId;
    }

    public Long getRenterId() {
        return renterId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public BookingStatus getStatus() {
        return status;
    }

    /**
     * Set the status directly. Callers must ask {@link BookingStateMachine} first — the service
     * layer does this, the same way it asks OwnershipGuard before a write.
     */
    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    public Long getVersion() {
        return version;
    }
}
