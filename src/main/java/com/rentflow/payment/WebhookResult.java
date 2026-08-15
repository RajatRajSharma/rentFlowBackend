package com.rentflow.payment;

/**
 * What the handler did with a delivery. All three are a 200 — the gateway asked a question
 * and got an answer, and only a genuinely broken payload deserves anything else.
 *
 * Returning this rather than {@code void} means the outcome is visible in logs and in tests
 * without inspecting side effects. "Did the duplicate get ignored?" should be answerable
 * directly, not inferred from a row count.
 */
public enum WebhookResult {

    /** First delivery of an event we act on. State changed. */
    PROCESSED,

    /** We had already handled this event id. Nothing happened, and nothing should have. */
    DUPLICATE,

    /** Correctly signed, but not an event type we act on. */
    IGNORED
}
