package com.rentflow.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Stripe, in test mode, over its REST API.
 *
 * Deliberately the raw HTTP API via {@link RestClient} rather than Stripe's Java SDK. The
 * SDK holds its base URL in a static field and is awkward to point elsewhere, whereas a
 * configurable base URL means the Day 15 WireMock tests can stand up a fake Stripe and
 * exercise declines, timeouts and duplicate responses against this exact class — not a
 * mock of it. The wire format is a handful of form fields; the SDK was not buying much.
 *
 * Enabled with {@code app.payment.gateway=stripe}. Without real test keys the app runs on
 * {@link FakeGateway} instead, so a fresh clone works with no secrets.
 */
@Component
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "stripe")
public class StripeGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeGateway.class);

    private final RestClient client;
    private final String webhookSecret;
    private final Duration webhookTolerance;

    public StripeGateway(RestClient.Builder builder,
                         @Value("${app.payment.stripe.base-url}") String baseUrl,
                         @Value("${app.payment.stripe.api-key}") String apiKey,
                         @Value("${app.payment.stripe.webhook-secret}") String webhookSecret,
                         @Value("${app.payment.webhook-tolerance-seconds:300}") long toleranceSeconds) {

        if (apiKey.isBlank() || apiKey.startsWith("sk_test_xxx")) {
            // Fail at startup, not at the first customer's payment. A placeholder key
            // configured by accident is a deployment mistake worth being loud about.
            throw new IllegalStateException(
                    "app.payment.gateway=stripe but STRIPE_API_KEY is unset or still the placeholder");
        }

        this.client = builder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.webhookSecret = webhookSecret;
        this.webhookTolerance = Duration.ofSeconds(toleranceSeconds);
    }

    @Override
    public GatewayIntent createIntent(GatewayIntentRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", String.valueOf(toMinorUnits(request.amount())));
        form.add("currency", request.currency().toLowerCase());
        form.add("description", request.description());
        // Metadata is free-form on Stripe's side; this is what makes a charge in their
        // dashboard traceable back to a booking without going through our database.
        form.add("metadata[booking_id]", String.valueOf(request.bookingId()));
        form.add("automatic_payment_methods[enabled]", "true");

        try {
            JsonNode body = client.post()
                    .uri("/v1/payment_intents")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    // Stripe's own dedupe. Same key + same params within 24h replays the
                    // original response instead of creating a second intent — which is why
                    // a retry that reaches Stripe still cannot double-charge.
                    .header("Idempotency-Key", request.idempotencyKey())
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null || !body.hasNonNull("id")) {
                throw new PaymentGatewayException("Stripe returned no payment intent id");
            }
            return new GatewayIntent(
                    body.get("id").asText(),
                    body.path("client_secret").asText(null));

        } catch (RestClientException ex) {
            // Covers both "Stripe said no" and "Stripe never answered". The caller must not
            // be able to tell them apart — see PaymentGateway#createIntent.
            log.warn("Stripe intent creation failed for key {}", request.idempotencyKey(), ex);
            throw new PaymentGatewayException("Payment gateway did not accept the request", ex);
        }
    }

    @Override
    public WebhookEvent parseWebhook(String rawPayload, String signatureHeader) {
        return StripeStyleWebhooks.parse(rawPayload, signatureHeader, webhookSecret, webhookTolerance);
    }

    @Override
    public String name() {
        return "stripe";
    }

    /**
     * Stripe bills in the currency's smallest unit — paise, not rupees. Sending 2000
     * when you meant ₹2000 charges ₹20, which is the kind of bug that only shows up in
     * production. Our column is NUMERIC(12,2), so this is exact; HALF_UP is belt and
     * braces for a currency with different minor units later.
     */
    private static long toMinorUnits(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
