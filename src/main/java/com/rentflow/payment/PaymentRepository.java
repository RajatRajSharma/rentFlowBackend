package com.rentflow.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Data access for payments. The two lookups that matter are by idempotency key (the
 * dedupe path) and by gateway ref (the webhook path) — everything else is reporting.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * The replay lookup. If a retried pay request already produced rows, these are they,
     * and we return them instead of charging again.
     *
     * Takes the full set of derived keys (fee + deposit) rather than a prefix match:
     * a LIKE 'b7:abc:%' would also match a key of "b7:abc-extra:FEE", which is a subtle
     * way to hand one caller another caller's payments.
     */
    List<Payment> findByIdempotencyKeyIn(List<String> idempotencyKeys);

    /** Everything charged for a booking, oldest first. */
    List<Payment> findByBookingIdOrderByIdAsc(Long bookingId);

    /** The webhook path (Day 13): the gateway only knows its own id for the intent. */
    Optional<Payment> findByGatewayRef(String gatewayRef);

    /**
     * Record the gateway's reference, but only if we don't have one yet.
     *
     * A conditional UPDATE rather than saving the entity, and the difference matters under
     * retries. Concurrent attempts each load their own copy of this row at the same
     * {@code @Version}, so an entity save would let the first writer through and throw an
     * optimistic-lock failure at every other thread — for a write that agreed with theirs.
     * The reference is derived from the idempotency key and so is identical across those
     * threads, which makes "first one writes, the rest quietly no-op" exactly right.
     *
     * The {@code IS NULL} guard is what keeps it a no-op rather than a last-writer-wins
     * overwrite, so this stays safe to call on every replay.
     *
     * @return 1 if this call set the reference, 0 if it was already set
     */
    @Modifying
    @Query("UPDATE Payment p SET p.gatewayRef = :gatewayRef WHERE p.id = :id AND p.gatewayRef IS NULL")
    int attachGatewayRef(@Param("id") Long id, @Param("gatewayRef") String gatewayRef);
}
