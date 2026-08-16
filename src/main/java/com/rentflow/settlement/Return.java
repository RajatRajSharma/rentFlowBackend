package com.rentflow.settlement;

import com.rentflow.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * The record of an item coming back, and how its deposit was split. Maps to `returns` (V5).
 *
 * {@code booking_id} is UNIQUE in the schema: an item is returned once, and that constraint
 * is what stops a retried request releasing the deposit twice.
 */
@Entity
@Table(name = "returns")
public class Return extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition", nullable = false, updatable = false)
    private ReturnCondition condition;

    @Column(name = "deposit_deducted", nullable = false, updatable = false)
    private BigDecimal depositDeducted;

    @Column(name = "refund_amount", nullable = false, updatable = false)
    private BigDecimal refundAmount;

    @Column(updatable = false)
    private String notes;

    protected Return() {
    }

    public Return(Long bookingId, ReturnCondition condition, BigDecimal depositDeducted,
                  BigDecimal refundAmount, String notes) {
        this.bookingId = bookingId;
        this.condition = condition;
        this.depositDeducted = depositDeducted;
        this.refundAmount = refundAmount;
        this.notes = notes;
    }

    public Long getId() {
        return id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public ReturnCondition getCondition() {
        return condition;
    }

    public BigDecimal getDepositDeducted() {
        return depositDeducted;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isDamaged() {
        return condition == ReturnCondition.DAMAGED;
    }
}
