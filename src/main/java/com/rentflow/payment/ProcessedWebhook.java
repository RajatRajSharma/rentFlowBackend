package com.rentflow.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A webhook delivery we have already acted on. Maps to `processed_webhooks` (V5).
 *
 * The gateway's own event id is the primary key — no surrogate id — because uniqueness of
 * that value is the entire point of the table. Writes go through
 * {@link ProcessedWebhookRepository#claim}; this entity exists so the table is mapped and
 * readable (support asking "did we ever see evt_123?"), not as a write path.
 */
@Entity
@Table(name = "processed_webhooks")
public class ProcessedWebhook {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "event_type", updatable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedWebhook() {
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
