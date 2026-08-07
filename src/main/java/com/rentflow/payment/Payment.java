package com.rentflow.payment;

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

/**
 * One intended money movement for a booking. Maps to the `payments` table (V4).
 *
 * The row is created BEFORE the gateway is called, in PENDING. That ordering is the whole
 * point: it means the idempotency key is reserved in our database before any money can
 * move, so a retry that arrives while the first call is still in flight collides on the
 * UNIQUE constraint rather than starting a second charge.
 *
 * A payment is only ever moved to SUCCEEDED/FAILED by the gateway telling us so — the
 * webhook (Day 13) or the reconciliation sweep (Day 18). Nothing in the request path
 * decides its own outcome.
 */
@Entity
@Table(name = "payments")
public class Payment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /** The gateway's id for this intent. Null until the gateway call succeeds. */
    @Column(name = "gateway_ref")
    private String gatewayRef;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Payment() {
    }

    /** Every payment starts PENDING — we never assume an outcome we haven't been told. */
    public Payment(Long bookingId, PaymentType type, BigDecimal amount, String idempotencyKey) {
        this.bookingId = bookingId;
        this.type = type;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PENDING;
    }

    /**
     * Record the gateway's id for this intent. Separate from the outcome: knowing the
     * gateway accepted our intent is not the same as knowing the card cleared.
     */
    public void attachGatewayRef(String gatewayRef) {
        this.gatewayRef = gatewayRef;
    }

    /** The gateway confirmed the money moved. */
    public void markSucceeded() {
        this.status = PaymentStatus.SUCCEEDED;
        this.failureReason = null;
    }

    /** The gateway declined. The reason is kept for support and the Day 15 scenarios. */
    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    public Long getId() {
        return id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public String getGatewayRef() {
        return gatewayRef;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentType getType() {
        return type;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Long getVersion() {
        return version;
    }
}
