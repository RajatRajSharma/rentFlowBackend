package com.rentflow.payment.gateway;

/**
 * The payment provider, behind one small interface — so the service is reasonable without a
 * network, failure modes are stageable, and Stripe could become Razorpay.
 *
 * Implementations must be safe to call twice with the same
 * {@link GatewayIntentRequest#idempotencyKey()}: the second call replays the first's intent.
 */
public interface PaymentGateway {

    /**
     * Ask the gateway to prepare a charge. Returning normally means it accepted the intent,
     * NOT that money moved — the outcome arrives later via the webhook.
     *
     * @throws PaymentGatewayException if the gateway rejected us or could not be reached. A
     *         timeout is deliberately indistinguishable from a rejection: both are "unknown",
     *         so the payment stays PENDING and reconciliation settles it.
     */
    GatewayIntent createIntent(GatewayIntentRequest request);

    /**
     * Verify an incoming webhook and translate it into our vocabulary. Here rather than in a
     * service because the wire format is entirely the provider's business.
     *
     * @param rawPayload the body EXACTLY as received — signatures cover the raw bytes, so
     *        anything that re-serialises the JSON first will fail to verify.
     * @throws WebhookSignatureException if this didn't come from our gateway, or is too old.
     *         An exception rather than a flag, because a flag can be forgotten.
     */
    WebhookEvent parseWebhook(String rawPayload, String signatureHeader);

    /**
     * Ask the gateway what actually happened to an intent — the "the webhook never arrived"
     * path. Must never throw: an unreachable gateway is {@link GatewayPaymentStatus#UNKNOWN},
     * and the sweep tries again later rather than inventing an outcome.
     */
    GatewayPaymentStatus fetchStatus(String gatewayRef);

    /** Which provider this is, for logs and the {@code /api/version}-style diagnostics. */
    String name();
}
