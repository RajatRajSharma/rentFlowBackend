package com.rentflow.payment.gateway;

/**
 * The webhook payload didn't come from our gateway — bad signature, missing header,
 * malformed body, or a timestamp far enough outside our tolerance to look like a replay.
 *
 * Handled as 400, deliberately, rather than 401/403. A gateway is not a user being denied
 * access; it is a caller sending us something we can't accept. And the response matters
 * operationally: on a 4xx Stripe stops retrying, which is what we want for a payload that
 * will never become valid, whereas a 5xx would have it redeliver for days.
 */
public class WebhookSignatureException extends RuntimeException {

    public WebhookSignatureException(String message) {
        super(message);
    }

    public WebhookSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
