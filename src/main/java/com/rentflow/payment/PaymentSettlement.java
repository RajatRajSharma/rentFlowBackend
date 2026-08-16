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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * What it means for a payment to resolve: status, ledger, booking, event — in that order.
 *
 * Extracted from the webhook handler because reconciliation (the "webhook never arrived"
 * sweep) must do exactly the same thing. Two copies of this would drift, and the drift would
 * be in accounting. MANDATORY propagation: the caller owns the transaction boundary.
 */
@Service
public class PaymentSettlement {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettlement.class);

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final BookingStateMachine stateMachine;
    private final LedgerService ledgerService;
    private final EventPublisher events;

    public PaymentSettlement(PaymentRepository paymentRepository,
                             BookingRepository bookingRepository,
                             BookingStateMachine stateMachine,
                             LedgerService ledgerService,
                             EventPublisher events) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.stateMachine = stateMachine;
        this.ledgerService = ledgerService;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void applySuccess(Payment payment) {
        // Reachable without a duplicate delivery: reconciliation settles by polling, and a
        // webhook may land for a payment it already settled.
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("Payment {} was already SUCCEEDED — nothing to do", payment.getId());
            return;
        }

        payment.markSucceeded();
        paymentRepository.saveAndFlush(payment);

        postLedgerFor(payment);

        // Announced, not acted on: delivery waits for this transaction to commit.
        events.publish(PaymentSucceeded.of(
                payment.getId(), payment.getBookingId(), payment.getType(), payment.getAmount()));

        confirmBookingIfFullyPaid(payment.getBookingId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyFailure(Payment payment, String reason) {
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

            // Settlement owns refunds: only it knows the damage/refund split.
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

        boolean allCleared = paymentRepository.findByBookingIdOrderByIdAsc(bookingId).stream()
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

        events.publish(BookingConfirmed.of(bookingId, booking.getItemId(), booking.getRenterId()));
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
