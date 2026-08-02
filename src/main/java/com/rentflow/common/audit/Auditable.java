package com.rentflow.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Base class that gives every entity created_at / updated_at columns,
 * defined once instead of repeated on every table.
 *
 * @MappedSuperclass = "these fields are inherited into the child's table,
 * but this class is not itself a table."
 * @CreationTimestamp / @UpdateTimestamp = Hibernate fills these automatically.
 */
@MappedSuperclass
public abstract class Auditable {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
