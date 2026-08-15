package com.rentflow.support;

import com.rentflow.payment.gateway.FakeGateway;
import com.rentflow.payment.gateway.GatewayIntent;
import com.rentflow.payment.gateway.GatewayIntentRequest;
import com.rentflow.payment.gateway.PaymentGateway;
import com.rentflow.payment.gateway.PaymentGatewayException;
import com.rentflow.payment.gateway.WebhookEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A gateway that can be told to break. Delegates to {@link FakeGateway} otherwise, so
 * webhook signing stays real — only the outward call is staged.
 */
public class ControllableGateway implements PaymentGateway {

    private final FakeGateway delegate;
    private final AtomicInteger intentCalls = new AtomicInteger();

    private volatile String intentFailure;   // null = healthy

    public ControllableGateway(FakeGateway delegate) {
        this.delegate = delegate;
    }

    /** Every createIntent from now on fails — an outage, a timeout, a decline at the door. */
    public void breakIntents(String reason) {
        this.intentFailure = reason;
    }

    public void healIntents() {
        this.intentFailure = null;
    }

    public int intentCalls() {
        return intentCalls.get();
    }

    @Override
    public GatewayIntent createIntent(GatewayIntentRequest request) {
        intentCalls.incrementAndGet();
        String reason = intentFailure;
        if (reason != null) {
            throw new PaymentGatewayException(reason);
        }
        return delegate.createIntent(request);
    }

    @Override
    public WebhookEvent parseWebhook(String rawPayload, String signatureHeader) {
        return delegate.parseWebhook(rawPayload, signatureHeader);
    }

    @Override
    public String name() {
        return "controllable";
    }

    /** @Primary, so the services under test get this one and not the raw fake. */
    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public ControllableGateway controllableGateway(FakeGateway delegate) {
            return new ControllableGateway(delegate);
        }
    }
}
