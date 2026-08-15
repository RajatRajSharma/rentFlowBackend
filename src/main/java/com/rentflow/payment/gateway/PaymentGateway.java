package com.rentflow.payment.gateway;

/**
 * The payment provider, behind one method.
 *
 * An interface rather than calling Stripe directly from the service, for three reasons
 * that all showed up in this project:
 *
 *  1. The service can be reasoned about without a network. {@code PaymentService} has real
 *     logic — ownership, state checks, idempotency — and none of it should need a card.
 *  2. Failure modes become testable. The Day 15 scenarios (decline, timeout, duplicate)
 *     are trivial to stage against a fake and near-impossible to stage against a live
 *     gateway on demand.
 *  3. Providers get swapped. Stripe and Razorpay differ in every detail except this shape.
 *
 * Implementations must be safe to call twice with the same
 * {@link GatewayIntentRequest#idempotencyKey()} — the second call returns the first
 * call's intent rather than creating a new one.
 */
public interface PaymentGateway {

    /**
     * Ask the gateway to prepare a charge.
     *
     * Returning normally means the gateway accepted the intent, NOT that money moved.
     * The outcome arrives later via the webhook.
     *
     * @throws PaymentGatewayException if the gateway rejected the request or could not be
     *         reached. A timeout is deliberately not distinguishable from a rejection here:
     *         the caller must treat both as "unknown", leave the payment PENDING, and let
     *         reconciliation settle it.
     */
    GatewayIntent createIntent(GatewayIntentRequest request);

    /**
     * Verify an incoming webhook and translate it into our vocabulary.
     *
     * On the gateway rather than in a service because the wire format — the signature
     * scheme, the event names, where the payment id hides in the envelope — is entirely
     * the provider's business. {@code WebhookService} should never need to know that
     * Stripe says {@code payment_intent.payment_failed}.
     *
     * @param rawPayload the body EXACTLY as received. Signatures are computed over the raw
     *        bytes, so anything that re-serialises the JSON first will fail to verify.
     * @throws WebhookSignatureException if this did not come from our gateway, or is too
     *         old to accept. Never return a "not verified" flag — a caller can forget to
     *         check that; an exception cannot be ignored by accident.
     */
    WebhookEvent parseWebhook(String rawPayload, String signatureHeader);

    /** Which provider this is, for logs and the {@code /api/version}-style diagnostics. */
    String name();
}
