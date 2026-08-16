package com.rentflow.payment;

import com.rentflow.common.exception.NotFoundException;
import com.rentflow.payment.gateway.PaymentGateway;
import com.rentflow.payment.gateway.WebhookEvent;
import com.rentflow.payment.gateway.WebhookOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway's side of settling a payment: verify, dedupe, then hand off.
 *
 * Gateways deliver at-least-once, so the event id is claimed before any work and both share
 * one transaction — a failure frees the id for the retry, a duplicate writes nothing. What
 * happens to the payment itself lives in {@link PaymentSettlement}, which reconciliation
 * shares.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final PaymentGateway gateway;
    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookRepository processedWebhookRepository;
    private final PaymentSettlement settlement;

    public WebhookService(PaymentGateway gateway,
                          PaymentRepository paymentRepository,
                          ProcessedWebhookRepository processedWebhookRepository,
                          PaymentSettlement settlement) {
        this.gateway = gateway;
        this.paymentRepository = paymentRepository;
        this.processedWebhookRepository = processedWebhookRepository;
        this.settlement = settlement;
    }

    /**
     * Verify, dedupe, and apply one delivery.
     *
     * @throws com.rentflow.payment.gateway.WebhookSignatureException if it isn't genuinely
     *         from our gateway → 400, which also stops a retry that can never succeed.
     */
    @Transactional
    public WebhookResult handle(String rawPayload, String signatureHeader) {
        WebhookEvent event = gateway.parseWebhook(rawPayload, signatureHeader);

        if (event.outcome() == WebhookOutcome.IGNORED) {
            // Not claimed: nothing to be idempotent about, and recording every event we
            // ignore would grow the table for no benefit.
            log.debug("Ignoring webhook {} of type {}", event.eventId(), event.eventType());
            return WebhookResult.IGNORED;
        }

        // THE DEDUPE. The insert is the check — see ProcessedWebhookRepository#claim.
        if (processedWebhookRepository.claim(event.eventId(), event.eventType()) == 0) {
            log.info("Duplicate webhook {} — no-op", event.eventId());
            return WebhookResult.DUPLICATE;
        }

        Payment payment = paymentRepository.findByGatewayRef(event.gatewayRef())
                .orElseThrow(() -> new NotFoundException("Payment for gateway ref", event.gatewayRef()));

        switch (event.outcome()) {
            case SUCCEEDED -> settlement.applySuccess(payment);
            case FAILED -> settlement.applyFailure(payment, event.failureReason());
            default -> throw new IllegalStateException("Unreachable: " + event.outcome());
        }
        return WebhookResult.PROCESSED;
    }
}
