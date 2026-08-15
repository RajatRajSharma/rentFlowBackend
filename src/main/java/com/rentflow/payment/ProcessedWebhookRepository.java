package com.rentflow.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The webhook dedupe table, accessed through exactly one operation: claim.
 *
 * There is deliberately no {@code existsByEventId} here. A check-then-insert would be the
 * same check-then-act race the whole booking engine exists to avoid — two copies of an event
 * arriving together would both see "not processed" and both go on to write ledger entries.
 * The insert IS the check.
 */
@Repository
public interface ProcessedWebhookRepository extends JpaRepository<ProcessedWebhook, String> {

    /**
     * Claim this event id, atomically. Returns 1 if we are the first to see it, 0 if it was
     * already claimed.
     *
     * A native {@code INSERT ... ON CONFLICT DO NOTHING} rather than a JPA save, because
     * only the database can make "test and set" a single indivisible step. Two concurrent
     * deliveries of the same event serialise on the primary key: the second blocks until the
     * first commits, then sees the conflict and inserts nothing.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_webhooks (event_id, event_type)
            VALUES (:eventId, :eventType)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("eventId") String eventId, @Param("eventType") String eventType);
}
