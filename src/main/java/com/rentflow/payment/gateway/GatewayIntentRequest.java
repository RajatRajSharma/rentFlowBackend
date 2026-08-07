package com.rentflow.payment.gateway;

import java.math.BigDecimal;

/**
 * What we ask a gateway to do: hold this much money, for this booking, exactly once.
 *
 * A record rather than a long parameter list so adding a field (currency, customer id,
 * statement descriptor) doesn't ripple through every implementation's signature.
 *
 * @param idempotencyKey the SAME key stored on our payment row. Passing it through means
 *                       both layers dedupe: our UNIQUE constraint stops a second row, and
 *                       the gateway's own idempotency stops a second charge even if our
 *                       process dies between reserving the row and calling out.
 * @param amount         in major units (rupees), not paise — converted at the edge by the
 *                       implementation that actually needs minor units.
 * @param bookingId      attached as gateway metadata, so a human staring at the gateway
 *                       dashboard can trace a charge back to a booking without a join.
 */
public record GatewayIntentRequest(
        String idempotencyKey,
        BigDecimal amount,
        String currency,
        Long bookingId,
        String description
) {
}
