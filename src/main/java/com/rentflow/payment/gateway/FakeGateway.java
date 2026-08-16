package com.rentflow.payment.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * A gateway that takes no money and needs no keys — the default, so a fresh clone runs end to
 * end with an empty {@code .env}. Swapped out with {@code app.payment.gateway=stripe}.
 *
 * It keeps the property that matters: the same idempotency key always derives the same
 * reference, so it survives restarts and behaves identically across instances.
 */
@Component
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "fake", matchIfMissing = true)
public class FakeGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakeGateway.class);

    private final String webhookSecret;
    private final Duration webhookTolerance;

    public FakeGateway(@Value("${app.payment.fake.webhook-secret}") String webhookSecret,
                       @Value("${app.payment.webhook-tolerance-seconds:300}") long toleranceSeconds) {
        this.webhookSecret = webhookSecret;
        this.webhookTolerance = Duration.ofSeconds(toleranceSeconds);
    }

    @Override
    public GatewayIntent createIntent(GatewayIntentRequest request) {
        String ref = "pi_fake_" + fingerprint(request.idempotencyKey());
        log.info("FakeGateway: intent {} for booking {} amount {} {}",
                ref, request.bookingId(), request.amount(), request.currency());

        return new GatewayIntent(ref, ref + "_secret_" + fingerprint(ref));
    }

    /**
     * Verified exactly the way Stripe's are, with the fake's own secret — a fake that waved
     * signatures through would leave a public endpoint's only defence untested.
     */
    @Override
    public WebhookEvent parseWebhook(String rawPayload, String signatureHeader) {
        return StripeStyleWebhooks.parse(rawPayload, signatureHeader, webhookSecret, webhookTolerance);
    }

    /**
     * Always PENDING: this gateway keeps no state, so it has nothing to report. Reconciliation
     * therefore never settles anything locally, which is the honest answer rather than a lie.
     */
    @Override
    public GatewayPaymentStatus fetchStatus(String gatewayRef) {
        return GatewayPaymentStatus.PENDING;
    }

    @Override
    public String name() {
        return "fake";
    }

    /**
     * Sign a payload the way this gateway expects. Local tooling and tests produce genuine
     * deliveries with it — there is no bypass, only a way to hold the key.
     */
    public String signatureHeader(String rawPayload, Instant timestamp) {
        return StripeStyleWebhooks.signatureHeader(rawPayload, webhookSecret, timestamp);
    }

    /**
     * A short, stable, opaque id for a key — hashed, so a reference handed to a client never
     * leaks the key that produced it.
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
