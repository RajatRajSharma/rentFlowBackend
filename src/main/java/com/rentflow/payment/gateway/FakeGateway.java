package com.rentflow.payment.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * A gateway that takes no money and needs no keys — the default, so a fresh clone runs
 * end to end with an empty {@code .env}.
 *
 * This is not a test double dressed up as production code; it is the honest local
 * implementation of the same contract, and it keeps the one property that matters:
 * <b>the same idempotency key always yields the same reference</b>. That is derived, not
 * remembered, so it survives a restart and behaves identically across instances — which
 * means the idempotency behaviour you exercise locally is the behaviour you get in prod.
 *
 * Swapped out by setting {@code app.payment.gateway=stripe}.
 */
@Component
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "fake", matchIfMissing = true)
public class FakeGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakeGateway.class);

    @Override
    public GatewayIntent createIntent(GatewayIntentRequest request) {
        String ref = "pi_fake_" + fingerprint(request.idempotencyKey());
        log.info("FakeGateway: intent {} for booking {} amount {} {}",
                ref, request.bookingId(), request.amount(), request.currency());

        return new GatewayIntent(ref, ref + "_secret_" + fingerprint(ref));
    }

    @Override
    public String name() {
        return "fake";
    }

    /**
     * A short, stable, opaque id for a key. Hex of the string's hash rather than the key
     * itself, so a reference handed to a client never leaks the key that produced it —
     * the same reason you don't put a raw idempotency key in a URL.
     */
    private static String fingerprint(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;                 // FNV-1a, 64-bit
        for (byte b : bytes) {
            hash = (hash ^ (b & 0xff)) * 0x100000001b3L;
        }
        return HexFormat.of().toHexDigits(hash);
    }
}
