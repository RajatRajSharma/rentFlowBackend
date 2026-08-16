package com.rentflow.settlement;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStateMachine;
import com.rentflow.booking.BookingStatus;
import com.rentflow.common.exception.InvalidRequestException;
import com.rentflow.common.exception.NotFoundException;
import com.rentflow.event.EventPublisher;
import com.rentflow.event.ReturnRecorded;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.ledger.LedgerAccount;
import com.rentflow.ledger.LedgerEntry;
import com.rentflow.ledger.LedgerService;
import com.rentflow.security.OwnershipGuard;
import com.rentflow.settlement.dto.RecordReturnRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * What happens when the item comes back: the deposit stops being ours to hold and becomes
 * somebody's money — the renter's, the owner's, or both.
 *
 * This is the movement the double-entry ledger was built for. A single amount column could
 * record "5000 deposit"; only two sides can record "1500 of it is now the owner's".
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final ReturnRepository returnRepository;
    private final BookingRepository bookingRepository;
    private final ItemRepository itemRepository;
    private final BookingStateMachine stateMachine;
    private final LedgerService ledgerService;
    private final OwnershipGuard ownershipGuard;
    private final EventPublisher events;

    public SettlementService(ReturnRepository returnRepository,
                             BookingRepository bookingRepository,
                             ItemRepository itemRepository,
                             BookingStateMachine stateMachine,
                             LedgerService ledgerService,
                             OwnershipGuard ownershipGuard,
                             EventPublisher events) {
        this.returnRepository = returnRepository;
        this.bookingRepository = bookingRepository;
        this.itemRepository = itemRepository;
        this.stateMachine = stateMachine;
        this.ledgerService = ledgerService;
        this.ownershipGuard = ownershipGuard;
        this.events = events;
    }

    /**
     * Record that an item came back, split its deposit, and move the booking on.
     *
     * @param ownerId the ITEM's owner — they took the item back, so they are the one who can
     *                say what state it is in. The renter cannot close their own damage claim.
     */
    @Transactional
    public Return recordReturn(Long bookingId, Long ownerId, RecordReturnRequest request) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
        Item item = itemRepository.findById(booking.getItemId())
                .orElseThrow(() -> new NotFoundException("Item", booking.getItemId()));
        ownershipGuard.requireOwner(item.getOwnerId(), ownerId);

        // Replay rather than reject: a retried confirmation is the same confirmation, and the
        // UNIQUE on booking_id would otherwise surface as a 409 for a request that succeeded.
        Return existing = returnRepository.findByBookingId(bookingId).orElse(null);
        if (existing != null) {
            log.debug("Booking {} was already returned — replaying record {}", bookingId, existing.getId());
            return existing;
        }

        stateMachine.requireTransition(booking.getStatus(), BookingStatus.RETURNED);

        BigDecimal deposit = booking.getDepositAmount();
        BigDecimal deducted = validatedDeduction(request, deposit);
        BigDecimal refund = deposit.subtract(deducted);

        Return record = returnRepository.save(new Return(
                bookingId, request.condition(), deducted, refund, request.notes()));

        postDepositSplit(bookingId, deposit, deducted, refund);

        booking.setStatus(BookingStatus.RETURNED);
        if (record.isDamaged()) {
            // A claim needs a human. DISPUTED is what keeps the deposit-release worker off it.
            stateMachine.requireTransition(BookingStatus.RETURNED, BookingStatus.DISPUTED);
            booking.setStatus(BookingStatus.DISPUTED);
        }
        bookingRepository.save(booking);

        events.publish(ReturnRecorded.of(bookingId, item.getId(), record.isDamaged(), deducted, refund));

        log.info("Booking {} returned {} — {} deducted, {} refundable",
                bookingId, request.condition(), deducted, refund);
        return record;
    }

    /**
     * The deposit leaves DEPOSIT_HELD and lands in the two accounts that describe whose money
     * it now is. Debits equal credits by construction: deducted + refund is the deposit.
     */
    private void postDepositSplit(Long bookingId, BigDecimal deposit,
                                  BigDecimal deducted, BigDecimal refund) {
        if (deposit.signum() == 0) {
            return;   // nothing was ever held, so there is nothing to release
        }

        List<LedgerEntry> lines = new ArrayList<>();
        lines.add(LedgerEntry.debit(bookingId, null, LedgerAccount.DEPOSIT_HELD, deposit));
        if (deducted.signum() > 0) {
            lines.add(LedgerEntry.credit(bookingId, null, LedgerAccount.OWNER_PAYABLE, deducted));
        }
        if (refund.signum() > 0) {
            lines.add(LedgerEntry.credit(bookingId, null, LedgerAccount.RENTER_REFUND, refund));
        }
        ledgerService.post(lines);
    }

    /** The database enforces these too; failing here gives the owner a usable message. */
    private static BigDecimal validatedDeduction(RecordReturnRequest request, BigDecimal deposit) {
        BigDecimal deducted = request.deductionOrZero();

        if (deducted.signum() > 0 && request.condition() != ReturnCondition.DAMAGED) {
            throw new InvalidRequestException("Nothing can be deducted from an undamaged return");
        }
        if (deducted.compareTo(deposit) > 0) {
            throw new InvalidRequestException(
                    "Cannot deduct " + deducted + " from a deposit of " + deposit);
        }
        return deducted;
    }
}
