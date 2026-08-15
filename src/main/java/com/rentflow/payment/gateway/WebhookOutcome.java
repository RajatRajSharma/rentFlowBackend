package com.rentflow.payment.gateway;

/**
 * What a webhook is telling us about a payment, reduced to the only three cases we act on.
 *
 * Providers emit dozens of event types. Translating them into this tiny enum at the gateway
 * boundary keeps every provider's vocabulary out of the service layer — {@code WebhookService}
 * should never have to know that Stripe says {@code payment_intent.payment_failed} while
 * Razorpay says {@code payment.failed}.
 */
public enum WebhookOutcome {

    /** The money moved. */
    SUCCEEDED,

    /** The gateway declined it. */
    FAILED,

    /**
     * A real, correctly-signed event we simply don't act on — {@code charge.updated} and
     * friends. Explicitly modelled rather than represented by a null, because "ignore this"
     * and "this couldn't be parsed" must lead to different responses: the first is a 200
     * that stops the gateway retrying, the second is a 400 that tells it something is wrong.
     */
    IGNORED
}
