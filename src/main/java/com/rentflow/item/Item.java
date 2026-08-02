package com.rentflow.item;

import com.rentflow.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

/**
 * A rentable listing. Maps to the `items` table (V2__items.sql).
 * ownerId links to the user who listed it — the basis for ownership checks.
 */
@Entity
@Table(name = "items")
public class Item extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    // BigDecimal for money — never double/float (rounding errors).
    @Column(name = "daily_rate", nullable = false)
    private BigDecimal dailyRate;

    @Column(name = "deposit_amount", nullable = false)
    private BigDecimal depositAmount;

    @Column(nullable = false)
    private String status = "ACTIVE";

    // @Version = optimistic locking. Hibernate bumps this on each update and rejects
    // a save if the row changed underneath you (prevents lost updates).
    @Version
    @Column(nullable = false)
    private Long version;

    protected Item() {
    }

    public Item(Long ownerId, String title, String description, BigDecimal dailyRate, BigDecimal depositAmount) {
        this.ownerId = ownerId;
        this.title = title;
        this.description = description;
        this.dailyRate = dailyRate;
        this.depositAmount = depositAmount;
        this.status = "ACTIVE";
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getDailyRate() {
        return dailyRate;
    }

    public void setDailyRate(BigDecimal dailyRate) {
        this.dailyRate = dailyRate;
    }

    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    public void setDepositAmount(BigDecimal depositAmount) {
        this.depositAmount = depositAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }
}
