package com.rentflow.payment.gateway;

/**
 * A verified webhook, in our vocabulary rather than the provider's.
 *
 * @param eventId     the gateway's unique id for this DELIVERY-worthy event (Stripe: "evt_...").
 *                    This is what makes duplicate detection possible, and it must identify the
 *                    event, not the payment — a payment legitimately generates several events.
 * @param gatewayRef  the payment intent the event is about ("pi_..."), which is how we find our
 *                    own payment row.
 * @param outcome     what to do about it.
 * @param failureReason provider's decline code (e.g. {@code card_declined}); null unless FAILED.
 */
public record WebhookEvent(
        String eventId,
        String eventType,
        String gatewayRef,
        WebhookOutcome outcome,
        String failureReason
) {
    /** A well-formed event we don't act on. Carries its id so it can still be recorded. */
    public static WebhookEvent ignored(String eventId, String eventType) {
        return new WebhookEvent(eventId, eventType, null, WebhookOutcome.IGNORED, null);
    }
}
