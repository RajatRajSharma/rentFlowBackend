package com.rentflow.payment.gateway;

/**
 * What a gateway hands back when it accepts an intent.
 *
 * Note what is NOT here: a success flag. Creating an intent means the gateway agreed to
 * try, not that money moved. The outcome arrives later, out of band, via the webhook.
 * Modelling it any other way is how you end up marking a booking CONFIRMED because an
 * HTTP call returned 200.
 *
 * @param gatewayRef   the gateway's id for this intent (Stripe: "pi_..."). Stored on the
 *                     payment row so the webhook can find its way back to us.
 * @param clientSecret the token the browser needs to complete the payment. Never stored —
 *                     it is a bearer credential for this one intent, and our database is
 *                     not where those belong. Re-issued on replay by asking the gateway
 *                     again with the same idempotency key.
 */
public record GatewayIntent(
        String gatewayRef,
        String clientSecret
) {
}
