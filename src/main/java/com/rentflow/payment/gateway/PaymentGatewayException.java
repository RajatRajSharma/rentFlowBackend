package com.rentflow.payment.gateway;

/**
 * The gateway rejected our request, or we could not reach it.
 *
 * Handled centrally as 502 Bad Gateway: the caller's request was fine, our downstream
 * wasn't. A 500 would blame us and a 400 would blame them — both would send the client
 * down the wrong path, and a client that retries a "bad request" forever is worse than
 * one that backs off on a 502.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
