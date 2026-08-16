package com.rentflow.payment.gateway;

/**
 * What the gateway says about an intent when we go and ask. The two negative answers are
 * kept apart on purpose: PENDING is "not yet", UNKNOWN is "we couldn't find out".
 */
public enum GatewayPaymentStatus {

    /** The money moved. Safe to settle. */
    SUCCEEDED,

    /** The gateway is done and it did not work. Safe to fail. */
    FAILED,

    /** Still in flight — the customer hasn't finished, or the gateway hasn't decided. */
    PENDING,

    /** We asked and got no usable answer. Change nothing; ask again next sweep. */
    UNKNOWN
}
