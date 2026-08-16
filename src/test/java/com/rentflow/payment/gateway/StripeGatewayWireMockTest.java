package com.rentflow.payment.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 15: the real {@link StripeGateway} against a fake Stripe over real HTTP. A mocked
 * gateway would skip the parts that actually break — wire format, units, header, timeouts.
 */
@DisplayName("StripeGateway against a stubbed Stripe")
class StripeGatewayWireMockTest {

    private static final String INTENTS = "/v1/payment_intents";
    private static final String API_KEY = "sk_test_abc123";

    private static final String INTENT_JSON = """
            {"id":"pi_3Nx","object":"payment_intent","client_secret":"pi_3Nx_secret_xyz"}""";

    private WireMockServer stripe;

    @BeforeEach
    void startStripe() {
        stripe = new WireMockServer(options().dynamicPort());
        stripe.start();
    }

    @AfterEach
    void stopStripe() {
        stripe.stop();
    }

    @Test
    @DisplayName("sends paise, the booking id and the idempotency key; returns the intent")
    void happyPath() {
        stubOk(INTENT_JSON);

        GatewayIntent intent = gateway().createIntent(request("key-1", "2000.00"));

        assertThat(intent.gatewayRef()).isEqualTo("pi_3Nx");
        assertThat(intent.clientSecret()).isEqualTo("pi_3Nx_secret_xyz");

        // ₹2000.00 must leave as 200000 paise. Sending 2000 would charge ₹20.
        stripe.verify(postRequestedFor(urlEqualTo(INTENTS))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withHeader("Idempotency-Key", equalTo("key-1"))
                .withRequestBody(containing("amount=200000"))
                .withRequestBody(containing("currency=inr")));
    }

    @Test
    @DisplayName("a declined card is a PaymentGatewayException, not a silent success")
    void cardDeclined() {
        stripe.stubFor(post(urlEqualTo(INTENTS)).willReturn(aResponse()
                .withStatus(402)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"code":"card_declined","message":"Your card was declined."}}""")));

        assertThatThrownBy(() -> gateway().createIntent(request("key-1", "2000.00")))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    @DisplayName("a hung gateway times out and fails the same way a decline does")
    void gatewayTimeout() {
        // Stripe accepts the request and then goes quiet — the case where money may or may
        // not have moved. Our read timeout is what stops the request thread hanging with it.
        stripe.stubFor(post(urlEqualTo(INTENTS)).willReturn(aResponse()
                .withFixedDelay(3_000)
                .withStatus(200)
                .withBody(INTENT_JSON)));

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> gateway(300).createIntent(request("key-1", "2000.00")))
                .as("indistinguishable from a decline on purpose: the caller must treat both as unknown")
                .isInstanceOf(PaymentGatewayException.class);

        assertThat(System.currentTimeMillis() - started)
                .as("we must give up on our schedule, not Stripe's")
                .isLessThan(3_000);
    }

    @Test
    @DisplayName("a 5xx from Stripe never becomes a payment we think was accepted")
    void gatewayError() {
        stripe.stubFor(post(urlEqualTo(INTENTS)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> gateway().createIntent(request("key-1", "2000.00")))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    @DisplayName("a 200 with no intent id is treated as a failure")
    void malformedSuccess() {
        stubOk("""
                {"object":"payment_intent"}""");

        assertThatThrownBy(() -> gateway().createIntent(request("key-1", "2000.00")))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("no payment intent id");
    }

    @Test
    @DisplayName("a retry replays the original intent — Stripe dedupes on our key")
    void retryWithTheSameKeyReplays() {
        stubOk(INTENT_JSON);
        StripeGateway gateway = gateway();

        GatewayIntent first = gateway.createIntent(request("key-1", "2000.00"));
        GatewayIntent retry = gateway.createIntent(request("key-1", "2000.00"));

        // Both calls carried the key, so Stripe returned the same intent rather than
        // creating a second one. That header is the whole reason a retry is safe.
        assertThat(retry.gatewayRef()).isEqualTo(first.gatewayRef());
        stripe.verify(2, postRequestedFor(urlEqualTo(INTENTS))
                .withHeader("Idempotency-Key", equalTo("key-1")));
    }

    @Test
    @DisplayName("a placeholder API key fails at startup, not at the first payment")
    void placeholderKeyRefusesToStart() {
        assertThatThrownBy(() -> new StripeGateway(RestClient.builder(), baseUrl(),
                "sk_test_xxx", "whsec_test", 300, 3_000, 10_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRIPE_API_KEY");

        assertThatCode(() -> gateway()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reconciliation reads the intent's status, and never guesses")
    void fetchStatusMapsStripesVocabulary() {
        assertThat(statusFor("""
                {"id":"pi_3Nx","status":"succeeded"}""")).isEqualTo(GatewayPaymentStatus.SUCCEEDED);

        assertThat(statusFor("""
                {"id":"pi_3Nx","status":"canceled"}""")).isEqualTo(GatewayPaymentStatus.FAILED);

        // A declined intent goes back to requires_payment_method — the error is what tells
        // it apart from an intent nobody has paid yet.
        assertThat(statusFor("""
                {"id":"pi_3Nx","status":"requires_payment_method",\
                "last_payment_error":{"code":"card_declined"}}""")).isEqualTo(GatewayPaymentStatus.FAILED);
        assertThat(statusFor("""
                {"id":"pi_3Nx","status":"requires_payment_method"}""")).isEqualTo(GatewayPaymentStatus.PENDING);

        assertThat(statusFor("""
                {"id":"pi_3Nx","status":"processing"}""")).isEqualTo(GatewayPaymentStatus.PENDING);
        assertThat(statusFor("""
                {"id":"pi_3Nx","status":"something_new"}""")).isEqualTo(GatewayPaymentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("an unreachable Stripe is UNKNOWN, not a failed payment")
    void fetchStatusSwallowsGatewayErrors() {
        stripe.stubFor(get(urlEqualTo(INTENTS + "/pi_3Nx")).willReturn(aResponse().withStatus(500)));

        assertThat(gateway().fetchStatus("pi_3Nx"))
                .as("settling a payment on a network error is the one unforgivable outcome")
                .isEqualTo(GatewayPaymentStatus.UNKNOWN);
    }

    // ------------------------------------------------------------------------- helpers

    private GatewayPaymentStatus statusFor(String intentJson) {
        stripe.stubFor(get(urlEqualTo(INTENTS + "/pi_3Nx")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(intentJson)));
        return gateway().fetchStatus("pi_3Nx");
    }

    private void stubOk(String body) {
        stripe.stubFor(post(urlEqualTo(INTENTS)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private StripeGateway gateway() {
        return gateway(10_000);
    }

    private StripeGateway gateway(long readTimeoutMs) {
        return new StripeGateway(RestClient.builder(), baseUrl(), API_KEY, "whsec_test",
                300, 3_000, readTimeoutMs);
    }

    private String baseUrl() {
        return "http://localhost:" + stripe.port();
    }

    private static GatewayIntentRequest request(String key, String amount) {
        return new GatewayIntentRequest(key, new BigDecimal(amount), "INR", 42L, "FEE for booking 42");
    }
}
