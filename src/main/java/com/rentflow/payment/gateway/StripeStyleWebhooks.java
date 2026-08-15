package com.rentflow.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Stripe's webhook signature scheme and event envelope, implemented once.
 *
 * Shared by {@link StripeGateway} and {@link FakeGateway} on purpose: the fake speaks the
 * same dialect as the real thing, so the verification code you exercise locally and in
 * tests is the identical code that guards production. A fake that skipped signing would
 * leave the one security-critical path in this feature untested.
 *
 * <h3>The scheme</h3>
 * <pre>
 *   header:         Stripe-Signature: t=1739500000,v1=5257a8...
 *   signed payload: "{t}.{raw body}"
 *   signature:      hex(HMAC-SHA256(signed payload, webhook secret))
 * </pre>
 *
 * Three details do the actual work, and all three are easy to get wrong:
 *
 * <ol>
 *   <li><b>The RAW body is signed</b>, not a re-serialised object. Parse-then-re-encode
 *       changes key order and whitespace, and the signature stops matching. This is why the
 *       controller takes a {@code String} body rather than a mapped DTO.</li>
 *   <li><b>The timestamp is inside the signed payload</b>, so an attacker can't replay a
 *       genuine old event with a fresh timestamp — changing it invalidates the signature.
 *       The tolerance window then bounds how long a captured request stays usable.</li>
 *   <li><b>The comparison is constant-time.</b> A normal {@code equals} returns faster the
 *       earlier it finds a mismatched byte, which leaks enough to forge a signature one
 *       byte at a time.</li>
 * </ol>
 */
final class StripeStyleWebhooks {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StripeStyleWebhooks() {
    }

    /**
     * Verify {@code signatureHeader} against {@code rawPayload}, then translate the envelope.
     *
     * @throws WebhookSignatureException on anything that means "this isn't a valid delivery
     *         from our gateway" — the caller turns that into a 400.
     */
    static WebhookEvent parse(String rawPayload, String signatureHeader,
                              String secret, Duration tolerance) {

        if (secret == null || secret.isBlank()) {
            // An unset secret must never mean "skip verification" — that would turn a
            // misconfiguration into an open endpoint that anyone could use to confirm
            // their own bookings for free.
            throw new WebhookSignatureException("No webhook secret is configured");
        }
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new WebhookSignatureException("Empty webhook payload");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new WebhookSignatureException("Missing webhook signature header");
        }

        Parsed parsed = parseHeader(signatureHeader);
        requireFreshTimestamp(parsed.timestamp(), tolerance);

        String expected = sign(rawPayload, secret, parsed.timestamp());
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                parsed.signature().getBytes(StandardCharsets.UTF_8))) {
            throw new WebhookSignatureException("Webhook signature does not match");
        }

        return translate(rawPayload);
    }

    /**
     * Produce the header value for a payload. Public within the package so tests and local
     * tooling sign exactly the way the verifier expects, rather than reimplementing the
     * scheme and drifting from it.
     */
    static String signatureHeader(String rawPayload, String secret, Instant timestamp) {
        long seconds = timestamp.getEpochSecond();
        return "t=" + seconds + ",v1=" + sign(rawPayload, secret, seconds);
    }

    // ------------------------------------------------------------------------- internals

    private static String sign(String rawPayload, String secret, long timestamp) {
        String signedPayload = timestamp + "." + rawPayload;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new WebhookSignatureException("Could not compute the webhook signature", ex);
        }
    }

    /** Pulls {@code t} and the first {@code v1} out of "t=...,v1=...". */
    private static Parsed parseHeader(String header) {
        Long timestamp = null;
        String signature = null;

        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            switch (pair[0].trim()) {
                case "t" -> {
                    try {
                        timestamp = Long.parseLong(pair[1].trim());
                    } catch (NumberFormatException ex) {
                        throw new WebhookSignatureException("Webhook timestamp is not a number");
                    }
                }
                // Stripe may send several v1 entries during a secret rotation; the first
                // that verifies is enough, and in practice the first is the current one.
                case "v1" -> signature = signature == null ? pair[1].trim() : signature;
                default -> { /* v0 and future schemes: ignore rather than reject */ }
            }
        }

        if (timestamp == null || signature == null) {
            throw new WebhookSignatureException("Malformed webhook signature header");
        }
        return new Parsed(timestamp, signature);
    }

    /**
     * Reject deliveries whose timestamp is too far from now — in EITHER direction. A far-future
     * timestamp is just as suspicious as an old one, and only checking the past leaves a
     * captured request replayable forever by anyone whose clock runs fast.
     */
    private static void requireFreshTimestamp(long timestamp, Duration tolerance) {
        long skew = Math.abs(Instant.now().getEpochSecond() - timestamp);
        if (skew > tolerance.toSeconds()) {
            throw new WebhookSignatureException(
                    "Webhook timestamp is outside the " + tolerance.toSeconds() + "s tolerance");
        }
    }

    /** Envelope → our vocabulary. Unknown event types are IGNORED, not errors. */
    private static WebhookEvent translate(String rawPayload) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawPayload);
        } catch (Exception ex) {
            throw new WebhookSignatureException("Webhook payload is not valid JSON", ex);
        }

        String eventId = root.path("id").asText(null);
        String eventType = root.path("type").asText(null);
        if (eventId == null || eventType == null) {
            throw new WebhookSignatureException("Webhook payload has no id or type");
        }

        JsonNode intent = root.path("data").path("object");
        String gatewayRef = intent.path("id").asText(null);

        return switch (eventType) {
            case "payment_intent.succeeded" ->
                    new WebhookEvent(eventId, eventType, gatewayRef, WebhookOutcome.SUCCEEDED, null);
            case "payment_intent.payment_failed" ->
                    new WebhookEvent(eventId, eventType, gatewayRef, WebhookOutcome.FAILED,
                            intent.path("last_payment_error").path("code").asText("unknown"));
            default -> WebhookEvent.ignored(eventId, eventType);
        };
    }

    private record Parsed(long timestamp, String signature) {
    }
}
