package com.rentflow.payment;

import com.rentflow.payment.gateway.GatewayPaymentStatus;
import com.rentflow.payment.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The safety net for "the webhook never arrived". Webhooks get lost — a deploy mid-delivery,
 * an outage, a firewall — and without this the renter is charged while the booking sits
 * PENDING_PAYMENT forever, holding dates nobody can use.
 */
@Component
public class ReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;
    private final PaymentSettlement settlement;
    private final TransactionTemplate transactionTemplate;
    private final Duration staleAfter;

    public ReconciliationWorker(PaymentRepository paymentRepository,
                                PaymentGateway gateway,
                                PaymentSettlement settlement,
                                TransactionTemplate transactionTemplate,
                                @Value("${app.workers.stale-payment-minutes:15}") long staleAfterMinutes) {
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.settlement = settlement;
        this.transactionTemplate = transactionTemplate;
        this.staleAfter = Duration.ofMinutes(staleAfterMinutes);
    }

    @Scheduled(fixedDelayString = "${app.workers.reconciliation-interval-ms:60000}",
               initialDelayString = "${app.workers.initial-delay-ms:30000}")
    public void scheduled() {
        Summary summary = sweep();
        if (summary.touchedAnything()) {
            log.info("Reconciliation: {}", summary);
        }
    }

    /**
     * Ask the gateway about every payment that has been PENDING too long, and settle the ones
     * it has an answer for. Returns what it did, so tests and logs can both read it.
     */
    public Summary sweep() {
        List<Payment> stale = paymentRepository.findStalePending(Instant.now().minus(staleAfter));
        int settled = 0;
        int failed = 0;
        int stillPending = 0;
        int unresolvable = 0;

        for (Payment payment : stale) {
            if (payment.getGatewayRef() == null) {
                // The intent call never came back with a reference, so there is nothing to ask
                // about. Deliberately NOT failed: an intent may exist that we simply lost.
                log.warn("Payment {} is stale with no gateway ref — needs a human", payment.getId());
                unresolvable++;
                continue;
            }

            GatewayPaymentStatus status = gateway.fetchStatus(payment.getGatewayRef());
            try {
                switch (status) {
                    case SUCCEEDED -> {
                        settleInOwnTransaction(payment.getId(), true, null);
                        settled++;
                    }
                    case FAILED -> {
                        settleInOwnTransaction(payment.getId(), false, "reconciled: gateway reports failed");
                        failed++;
                    }
                    case PENDING -> stillPending++;
                    case UNKNOWN -> unresolvable++;
                }
            } catch (RuntimeException ex) {
                // One bad row must not end the sweep — the next one may be the one that matters.
                log.error("Reconciling payment {} failed", payment.getId(), ex);
                unresolvable++;
            }
        }

        return new Summary(stale.size(), settled, failed, stillPending, unresolvable);
    }

    /**
     * Each payment settles in its own transaction, and is re-read inside it — a webhook may
     * have landed between the sweep's query and now.
     */
    private void settleInOwnTransaction(Long paymentId, boolean succeeded, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            Payment fresh = paymentRepository.findById(paymentId).orElseThrow();
            if (fresh.getStatus() != PaymentStatus.PENDING) {
                log.debug("Payment {} was settled by someone else — skipping", paymentId);
                return;
            }
            if (succeeded) {
                log.info("Reconciliation settling payment {} as SUCCEEDED — its webhook never arrived", paymentId);
                settlement.applySuccess(fresh);
            } else {
                settlement.applyFailure(fresh, reason);
            }
        });
    }

    /** What one sweep did. {@code unresolvable} is the number worth alerting on. */
    public record Summary(int examined, int settled, int failed, int stillPending, int unresolvable) {

        public boolean touchedAnything() {
            return settled > 0 || failed > 0 || unresolvable > 0;
        }
    }
}
