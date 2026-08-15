package com.rentflow.payment;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStateMachine;
import com.rentflow.booking.BookingStatus;
import com.rentflow.common.exception.NotFoundException;
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
 * Where a payment actually resolves. The pay endpoint only ever creates intents; this is the
 * one place that says a payment SUCCEEDED or FAILED, writes the ledger, and moves a booking
 * to CONFIRMED or PAYMENT_FAILED.
 *
 * <h3>Claim first, then act — in one transaction</h3>
 * Every gateway delivers at-least-once. Stripe retries for up to three days if it doesn't get
 * a 2xx, so the same event WILL arrive twice: after a timeout, after a deploy, after one of
 * our own 500s. The handler therefore claims the event id before doing anything, and the
 * claim and the work share a transaction:
 *
 * <ul>
 *   <li>Both commit → the event is recorded as done, and it is done.</li>
 *   <li>Anything throws → the claim rolls back too, so the gateway's retry gets a genuine
 *       second chance rather than being turned away by a marker for work that never
 *       happened.</li>
 * </ul>
 *
 * Getting that backwards — claiming in its own transaction, or acting first and recording
 * after — produces the two classic bugs: an event marked processed that wasn't, or a
 * duplicate that writes a second set of ledger entries and unbalances the books.
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

    public WebhookService(PaymentGateway gateway,
                          PaymentRepository paymentRepository,
                          ProcessedWebhookRepository processedWebhookRepository,
                          BookingRepository bookingRepository,
                          BookingStateMachine stateMachine,
                          LedgerService ledgerService) {
        this.gateway = gateway;
        this.paymentRepository = paymentRepository;
        this.processedWebhookRepository = processedWebhookRepository;
        this.bookingRepository = bookingRepository;
        this.stateMachine = stateMachine;
        this.ledgerService = ledgerService;
    }

    /**
     * Verify, dedupe, and apply one delivery.
     *
     * @throws com.rentflow.payment.gateway.WebhookSignatureException if it isn't genuinely
     *         from our gateway → 400, which also stops the gateway retrying a payload that
     *         will never become valid.
     */
    @Transactional
    public WebhookResult handle(String rawPayload, String signatureHeader) {
        WebhookEvent event = gateway.parseWebhook(rawPayload, signatureHeader);

        if (event.outcome() == com.rentflow.payment.gateway.WebhookOutcome.IGNORED) {
            // Not claimed: there is nothing to be idempotent about, and recording every
            // charge.updated we don't care about would grow the table for no benefit.
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
        // Belt and braces alongside the event-id dedupe: reconciliation (Day 18) can also
        // settle a payment, by polling rather than by event, so "already SUCCEEDED" is a
        // state we can legitimately arrive at without a duplicate delivery.
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("Payment {} was already SUCCEEDED — nothing to do", payment.getId());
            return;
        }

        payment.markSucceeded();
        paymentRepository.saveAndFlush(payment);

        postLedgerFor(payment);
        confirmBookingIfFullyPaid(payment.getBookingId());
    }

    private void applyFailure(Payment payment, String reason) {
        if (payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }
        payment.markFailed(reason);
        paymentRepository.saveAndFlush(payment);

        // No ledger entry, deliberately: no money moved. The ledger records what happened,
        // not what was attempted — that is what the payments table is for.

        Booking booking = lockBooking(payment.getBookingId());
        if (!stateMachine.canTransition(booking.getStatus(), BookingStatus.PAYMENT_FAILED)) {
            log.warn("Payment {} failed but booking {} is {} — leaving it alone",
                    payment.getId(), booking.getId(), booking.getStatus());
            return;
        }
        booking.setStatus(BookingStatus.PAYMENT_FAILED);
        bookingRepository.save(booking);

        // PAYMENT_FAILED is not in BookingStatus.BLOCKING, so the dates drop out of both the
        // overlap query and the DB exclusion constraint — the item is bookable again with no
        // extra step. That coupling is the reason the blocking set lives in one place.
        log.info("Booking {} moved to PAYMENT_FAILED ({}) — dates released", booking.getId(), reason);
    }

    // --------------------------------------------------------------------------- ledger

    /**
     * Record the money movement, as two balanced halves.
     *
     * <pre>
     *   FEE      RENTER_CASH debit  │  OWNER_PAYABLE credit   we now owe the owner
     *   DEPOSIT  RENTER_CASH debit  │  DEPOSIT_HELD  credit   we hold it, we don't own it
     * </pre>
     *
     * The difference between those two credits is the whole reason for a ledger: both are
     * cash sitting with us, but only one of them is ever ours to pay out.
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

            // Refunds are created by settlement on return (Day 18), which knows how much of
            // the deposit was claimed for damage and how much goes back. Posting a guess here
            // would be inventing accounting for a flow that doesn't exist yet. Unreachable
            // today — nothing creates a REFUND payment.
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
     * Confirm the booking once EVERY charge has cleared.
     *
     * Not on the first success: a booking has a fee and a deposit, and confirming when only
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
            // The money moved but the booking can't accept it — cancelled while the payment
            // was in flight, most likely. Loud, because a human now owes someone a refund and
            // nothing else in the system will notice.
            log.error("Payments for booking {} all cleared but it is {} — a refund is owed",
                    bookingId, booking.getStatus());
            return;
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);
        log.info("Booking {} CONFIRMED — all payments cleared", bookingId);
    }

    /**
     * SELECT ... FOR UPDATE. The fee and the deposit produce two independent events that the
     * gateway may deliver simultaneously; without this both could read the payment set, both
     * conclude everything has cleared, and both write the booking.
     */
    private Booking lockBooking(Long bookingId) {
        return bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
    }
}
