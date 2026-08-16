package com.rentflow.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Data access for payments. The two lookups that matter are by idempotency key (the
 * dedupe path) and by gateway ref (the webhook path) — everything else is reporting.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * The replay lookup. Takes the full set of derived keys rather than a prefix match,
     * because LIKE 'b7:abc:%' would also match "b7:abc-extra:FEE" — one caller's payments
     * handed to another. Ordered by id so a replay lists them exactly as the first call did.
     */
    List<Payment> findByIdempotencyKeyInOrderByIdAsc(List<String> idempotencyKeys);

    /** Everything charged for a booking, oldest first. */
    List<Payment> findByBookingIdOrderByIdAsc(Long bookingId);

    /** The webhook path (Day 13): the gateway only knows its own id for the intent. */
    Optional<Payment> findByGatewayRef(String gatewayRef);

    /**
     * Payments still PENDING long after they were created — the "webhook never arrived" set
     * that reconciliation sweeps. The cutoff keeps in-flight payments out of it.
     */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.status = com.rentflow.payment.PaymentStatus.PENDING
              AND p.createdAt < :cutoff
            ORDER BY p.id
            """)
    List<Payment> findStalePending(@Param("cutoff") Instant cutoff);

    /**
     * Record the gateway's reference, but only if we don't have one yet. A conditional UPDATE
     * rather than an entity save: concurrent retries all write the same derived reference, so
     * "first one wins, the rest no-op" beats failing them all on the optimistic lock.
     *
     * @return 1 if this call set the reference, 0 if it was already set
     */
    @Modifying
    @Query("UPDATE Payment p SET p.gatewayRef = :gatewayRef WHERE p.id = :id AND p.gatewayRef IS NULL")
    int attachGatewayRef(@Param("id") Long id, @Param("gatewayRef") String gatewayRef);
}
