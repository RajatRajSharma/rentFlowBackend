package com.rentflow.payment;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStateMachine;
import com.rentflow.booking.BookingStatus;
import com.rentflow.common.exception.NotFoundException;
import com.rentflow.event.BookingConfirmed;
import com.rentflow.event.EventPublisher;
import com.rentflow.event.PaymentSucceeded;
import com.rentflow.ledger.LedgerAccount;
import com.rentflow.ledger.LedgerEntry;
import com.rentflow.ledger.LedgerService;
import com.rentflow.payment.gateway.PaymentGateway;
import com.rentflow.payment.gateway.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Where a payment resolves: the one place that says SUCCEEDED or FAILED, writes the ledger,
 * and moves a booking to CONFIRMED or PAYMENT_FAILED.
 *
 * Gateways deliver at-least-once, so the event id is claimed before any work and both share
 * one transaction — a failure frees the id for the retry, a duplicate writes nothing.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final PaymentGateway gateway;
    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookRepository processedWebhookRepository;
    private final BookingRepository bookingRepository;
    private final BookingStateMachine stateMachine;
    private final LedgerService ledgerService;
    private final EventPublisher events;

    public WebhookService(PaymentGateway gateway,
                          PaymentRepository paymentRepository,
                          ProcessedWebhookRepository processedWebhookRepository,
                          BookingRepository bookingRepository,
                          BookingStateMachine stateMachine,
                          LedgerService ledgerService,
                          EventPublisher events) {
        this.gateway = gateway;
        this.paymentRepository = paymentRepository;
        this.processedWebhookRepository = processedWebhookRepository;
        this.bookingRepository = bookingRepository;
        this.stateMachine = stateMachine;
        this.ledgerService = ledgerService;
        this.events = events;
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

        if (event.outcome() == com.rentflow.payment.gateway.WebhookOutcome.IGNORED) {
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
            case SUCCEEDED -> applySuccess(payment);
            case FAILED -> applyFailure(payment, event.failureReason());
            default -> throw new IllegalStateException("Unreachable: " + event.outcome());
        }
        return WebhookResult.PROCESSED;
    }

    // ------------------------------------------------------------------------- outcomes

    private void applySuccess(Payment payment) {
        // Reconciliation (Day 18) settles by polling, not by event, so "already SUCCEEDED"
        // is reachable without a duplicate delivery.
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("Payment {} was already SUCCEEDED — nothing to do", payment.getId());
            return;
        }

        payment.markSucceeded();
        paymentRepository.saveAndFlush(payment);

        postLedgerFor(payment);

        // Announced, not acted on: delivery waits for this transaction to commit.
        events.publish(new PaymentSucceeded(
                payment.getId(), payment.getBookingId(), payment.getType(), payment.getAmount()));

        confirmBookingIfFullyPaid(payment.getBookingId());
    }

    private void applyFailure(Payment payment, String reason) {
        if (payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }
        payment.markFailed(reason);
        paymentRepository.saveAndFlush(payment);

        // No ledger entry: no money moved. The ledger records what happened, not what was
        // attempted — that is what the payments table is for.

        Booking booking = lockBooking(payment.getBookingId());
        if (!stateMachine.canTransition(booking.getStatus(), BookingStatus.PAYMENT_FAILED)) {
            log.warn("Payment {} failed but booking {} is {} — leaving it alone",
                    payment.getId(), booking.getId(), booking.getStatus());
            return;
        }
        booking.setStatus(BookingStatus.PAYMENT_FAILED);
        bookingRepository.save(booking);

        // PAYMENT_FAILED isn't in BookingStatus.BLOCKING, so the dates drop out of the overlap
        // query and the exclusion constraint at once — the item is bookable again, no extra step.
        log.info("Booking {} moved to PAYMENT_FAILED ({}) — dates released", booking.getId(), reason);
    }

    // --------------------------------------------------------------------------- ledger

    /**
     * <pre>
     *   FEE      RENTER_CASH debit  │  OWNER_PAYABLE credit   we now owe the owner
     *   DEPOSIT  RENTER_CASH debit  │  DEPOSIT_HELD  credit   we hold it, we don't own it
     * </pre>
     * Both credits are cash sitting with us; only one is ever ours to pay out.
     */
    private void postLedgerFor(Payment payment) {
        if (ledgerService.alreadyPostedFor(payment.getId())) {
            log.warn("Ledger entries already exist for payment {} — not posting again", payment.getId());
            return;
        }

        Long bookingId = payment.getBookingId();
        Long paymentId = payment.getId();

        List<LedgerEntry> lines = switch (payment.getType()) {
            case FEE -> List.of(
                    LedgerEntry.debit(bookingId, paymentId, LedgerAccount.RENTER_CASH, payment.getAmount()),
                    LedgerEntry.credit(bookingId, paymentId, LedgerAccount.OWNER_PAYABLE, payment.getAmount()));

            case DEPOSIT -> List.of(
                    LedgerEntry.debit(bookingId, paymentId, LedgerAccount.RENTER_CASH, payment.getAmount()),
                    LedgerEntry.credit(bookingId, paymentId, LedgerAccount.DEPOSIT_HELD, payment.getAmount()));

            // Settlement (Day 18) owns refunds: only it knows the damage/refund split.
            // Unreachable today — nothing creates a REFUND payment.
            case REFUND -> {
                log.warn("REFUND payment {} succeeded, but settlement owns its ledger entries", paymentId);
                yield List.of();
            }
        };

        if (!lines.isEmpty()) {
            ledgerService.post(lines);
        }
    }

    // -------------------------------------------------------------------------- booking

    /**
     * Confirm once EVERY charge has cleared — not on the first success. Confirming when only
     * the fee has cleared would hand over an item whose damage deposit was never taken.
     */
    private void confirmBookingIfFullyPaid(Long bookingId) {
        Booking booking = lockBooking(bookingId);

        List<Payment> payments = paymentRepository.findByBookingIdOrderByIdAsc(bookingId);
        boolean allCleared = payments.stream()
                .allMatch(p -> p.getStatus() == PaymentStatus.SUCCEEDED);

        if (!allCleared) {
            log.debug("Booking {} still has unsettled payments — not confirming yet", bookingId);
            return;
        }

        if (!stateMachine.canTransition(booking.getStatus(), BookingStatus.CONFIRMED)) {
            // Money moved but the booking can't accept it — cancelled mid-flight, most likely.
            // Loud, because a human now owes someone a refund and nothing else will notice.
            log.error("Payments for booking {} all cleared but it is {} — a refund is owed",
                    bookingId, booking.getStatus());
            return;
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);
        log.info("Booking {} CONFIRMED — all payments cleared", bookingId);

        events.publish(new BookingConfirmed(bookingId, booking.getItemId(), booking.getRenterId()));
    }

    /**
     * SELECT ... FOR UPDATE. The fee and deposit events can arrive together; without this both
     * could read the payment set, both conclude everything cleared, and both write the booking.
     */
    private Booking lockBooking(Long bookingId) {
        return bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
    }
}
