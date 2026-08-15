package com.rentflow.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The gateway's way in. Public — no JWT, because Stripe has no account here; the signature
 * IS the authentication, and it's a stronger one than a bearer token would be.
 *
 * <h3>Why the body is a String</h3>
 * The signature is computed over the raw bytes the gateway sent. Letting Spring bind this to
 * a DTO would mean verifying a re-serialised copy — different key order, different whitespace,
 * different bytes — and every signature would fail. So the raw text comes in, gets verified,
 * and only then gets parsed.
 *
 * <h3>Why almost everything is a 200</h3>
 * The status code is not decoration here; it is an instruction. A 2xx tells the gateway to
 * stop redelivering, a 5xx tells it to retry for days. So a duplicate and an event we don't
 * care about both answer 200 — they need no retry — while an unrecognised payment answers 404
 * and a bad signature answers 400, both of which correctly stop a delivery that will never
 * succeed. Anything genuinely transient falls through to a 500 and gets retried, which is
 * exactly what we want.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/webhooks/payments")
    public ResponseEntity<Map<String, String>> receive(
            @RequestBody String rawPayload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {

        WebhookResult result = webhookService.handle(rawPayload, signature);
        log.debug("Webhook handled: {}", result);

        // A body the gateway ignores, but that makes a curl or a support investigation
        // immediately readable.
        return ResponseEntity.ok(Map.of("result", result.name()));
    }
}
