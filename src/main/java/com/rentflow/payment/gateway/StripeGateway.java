package com.rentflow.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Stripe, in test mode, over its REST API — not the SDK, because a configurable base URL lets
 * the WireMock tests drive this exact class. Enabled with {@code app.payment.gateway=stripe}.
 */
@Component
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "stripe")
public class StripeGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final String webhookSecret;
    private final Duration webhookTolerance;

    public StripeGateway(RestClient.Builder builder,
                         @Value("${app.payment.stripe.base-url}") String baseUrl,
                         @Value("${app.payment.stripe.api-key}") String apiKey,
                         @Value("${app.payment.stripe.webhook-secret}") String webhookSecret,
                         @Value("${app.payment.webhook-tolerance-seconds:300}") long toleranceSeconds,
                         @Value("${app.payment.stripe.connect-timeout-ms:3000}") long connectTimeoutMs,
                         @Value("${app.payment.stripe.read-timeout-ms:10000}") long readTimeoutMs) {

        if (apiKey.isBlank() || apiKey.startsWith("sk_test_xxx")) {
            // Fail at startup, not at the first customer's payment.
            throw new IllegalStateException(
                    "app.payment.gateway=stripe but STRIPE_API_KEY is unset or still the placeholder");
        }

        this.client = builder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(timeouts(connectTimeoutMs, readTimeoutMs))
                .build();
        this.webhookSecret = webhookSecret;
        this.webhookTolerance = Duration.ofSeconds(toleranceSeconds);
    }

    /**
     * Bounded waits, because an unbounded one is an outage: without a read timeout a hung
     * Stripe holds our request threads until the pool is empty and the whole app is down.
     */
    private static ClientHttpRequestFactory timeouts(long connectMs, long readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));
        return factory;
    }

    @Override
    public GatewayIntent createIntent(GatewayIntentRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", String.valueOf(toMinorUnits(request.amount())));
        form.add("currency", request.currency().toLowerCase());
        form.add("description", request.description());
        // Makes a charge in Stripe's dashboard traceable to a booking without our database.
        form.add("metadata[booking_id]", String.valueOf(request.bookingId()));
        form.add("automatic_payment_methods[enabled]", "true");

        try {
            // Taken as text and parsed here, with the same mapper the webhook path uses.
            // Letting the HTTP layer bind it would tie us to whichever Jackson it prefers.
            String rawBody = client.post()
                    .uri("/v1/payment_intents")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    // Stripe's own dedupe: the same key within 24h replays the original
                    // intent, so a retry that reaches Stripe cannot double-charge.
                    .header("Idempotency-Key", request.idempotencyKey())
                    .body(form)
                    .retrieve()
                    .body(String.class);

            JsonNode body = parse(rawBody);
            if (!body.hasNonNull("id")) {
                throw new PaymentGatewayException("Stripe returned no payment intent id");
            }
            return new GatewayIntent(
                    body.get("id").asText(),
                    body.path("client_secret").asText(null));

        } catch (RestClientException ex) {
            // "Stripe said no" and "Stripe never answered" are the same exception on purpose
            // — see PaymentGateway#createIntent.
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

    private static JsonNode parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new PaymentGatewayException("Stripe returned an empty body");
        }
        try {
            return MAPPER.readTree(rawBody);
        } catch (Exception ex) {
            throw new PaymentGatewayException("Stripe returned a body we could not parse", ex);
        }
    }

    /**
     * Stripe bills in the smallest unit — paise, not rupees. Sending 2000 for ₹2000 would
     * charge ₹20. NUMERIC(12,2) makes this exact; HALF_UP guards other minor units later.
     */
    private static long toMinorUnits(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
