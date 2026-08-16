package com.rentflow.settlement;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingActivationWorker;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStatus;
import com.rentflow.common.exception.ForbiddenException;
import com.rentflow.common.exception.IllegalTransitionException;
import com.rentflow.common.exception.InvalidRequestException;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.ledger.LedgerAccount;
import com.rentflow.ledger.LedgerBalance;
import com.rentflow.ledger.LedgerEntry;
import com.rentflow.ledger.LedgerRepository;
import com.rentflow.payment.Payment;
import com.rentflow.payment.PaymentRepository;
import com.rentflow.payment.PaymentService;
import com.rentflow.payment.PaymentType;
import com.rentflow.payment.WebhookService;
import com.rentflow.payment.gateway.FakeGateway;
import com.rentflow.settlement.dto.RecordReturnRequest;
import com.rentflow.support.IntegrationTestBase;
import com.rentflow.user.Role;
import com.rentflow.user.User;
import com.rentflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 18: the item comes back and the deposit stops being ours to hold. The full arc —
 * pay, confirm, activate, return — because the interesting assertion is that the books still
 * balance at the end of it.
 */
@DisplayName("Settlement on return")
class SettlementIT extends IntegrationTestBase {

    private static final BigDecimal DAILY_RATE = new BigDecimal("1000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("5000.00");
    private static final BigDecimal FEE = new BigDecimal("2000.00");

    @Autowired private SettlementService settlementService;
    @Autowired private BookingActivationWorker activationWorker;
    @Autowired private PaymentService paymentService;
    @Autowired private WebhookService webhookService;
    @Autowired private ReturnRepository returnRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FakeGateway fakeGateway;

    private Long bookingId;
    private Long ownerId;
    private Long renterId;

    @BeforeEach
    void seed() {
        User owner = userRepository.save(new User("Owner", "owner@test.com", "x", Role.USER));
        User renter = userRepository.save(new User("Renter", "renter@test.com", "x", Role.USER));
        Item item = itemRepository.save(new Item(
                owner.getId(), "Sony A7 III", "2 batteries", DAILY_RATE, DEPOSIT));

        // A rental that has already started, so the activation worker has something to do.
        LocalDate start = LocalDate.now().minusDays(2);
        Booking booking = bookingRepository.save(new Booking(
                item.getId(), renter.getId(), start, start.plusDays(1), FEE, DEPOSIT));

        bookingId = booking.getId();
        ownerId = owner.getId();
        renterId = renter.getId();
    }

    @Test
    @DisplayName("an undamaged return sends the whole deposit back, and the books still balance")
    void okReturnRefundsEverything() {
        payAndActivate();

        Return record = settlementService.recordReturn(bookingId, ownerId, ok());

        assertThat(record.getDepositDeducted()).isEqualByComparingTo("0.00");
        assertThat(record.getRefundAmount()).isEqualByComparingTo(DEPOSIT);
        assertThat(reloadBooking().getStatus()).isEqualTo(BookingStatus.RETURNED);

        // 4 lines from the payments + 2 from releasing the deposit.
        List<LedgerEntry> entries = ledgerRepository.findByBookingIdOrderByIdAsc(bookingId);
        LedgerBalance balance = ledgerRepository.balanceFor(bookingId);

        System.out.printf("""

                ================= SETTLEMENT (booking %d) =================
                %s
                 total debit : %s
                 total credit: %s
                 balanced    : %s
                ==========================================================
                %n""",
                bookingId,
                entries.stream()
                        .map(e -> String.format(" %-14s debit %8s  credit %8s",
                                e.getAccount(), e.getDebit(), e.getCredit()))
                        .reduce((a, b) -> a + "%n".formatted() + b).orElse(""),
                balance.totalDebit(), balance.totalCredit(), balance.isBalanced());

        assertThat(entries).hasSize(6);
        assertThat(balance.isBalanced()).isTrue();

        // The deposit came in and went back out: DEPOSIT_HELD nets to zero.
        assertThat(net(LedgerAccount.DEPOSIT_HELD)).isEqualByComparingTo("0.00");
        assertThat(sumCredit(LedgerAccount.RENTER_REFUND)).isEqualByComparingTo(DEPOSIT);
        // The fee was never part of the deposit and is still the owner's.
        assertThat(sumCredit(LedgerAccount.OWNER_PAYABLE)).isEqualByComparingTo(FEE);
    }

    @Test
    @DisplayName("a damage claim splits the deposit and sends the booking to DISPUTED")
    void damagedReturnSplitsTheDeposit() {
        payAndActivate();

        Return record = settlementService.recordReturn(bookingId, ownerId,
                new RecordReturnRequest(ReturnCondition.DAMAGED, new BigDecimal("1500.00"), "lens scratched"));

        assertThat(record.getRefundAmount()).isEqualByComparingTo("3500.00");
        assertThat(reloadBooking().getStatus())
                .as("a claim is a human's to resolve, so it must not auto-close")
                .isEqualTo(BookingStatus.DISPUTED);

        assertThat(ledgerRepository.balanceFor(bookingId).isBalanced()).isTrue();
        assertThat(net(LedgerAccount.DEPOSIT_HELD)).isEqualByComparingTo("0.00");
        assertThat(sumCredit(LedgerAccount.RENTER_REFUND)).isEqualByComparingTo("3500.00");
        // The fee plus the damage claim: both are now owed to the owner.
        assertThat(sumCredit(LedgerAccount.OWNER_PAYABLE)).isEqualByComparingTo("3500.00");
    }

    @Test
    @DisplayName("a repeated confirmation replays instead of releasing twice")
    void repeatedConfirmationReplays() {
        payAndActivate();

        Return first = settlementService.recordReturn(bookingId, ownerId, ok());
        Return replay = settlementService.recordReturn(bookingId, ownerId, ok());

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(returnRepository.count()).isEqualTo(1);
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId))
                .as("a second deposit release would credit the renter twice")
                .hasSize(6);
    }

    @Test
    @DisplayName("only the item's owner can confirm a return")
    void renterCannotConfirmTheirOwnReturn() {
        payAndActivate();

        assertThatThrownBy(() -> settlementService.recordReturn(bookingId, renterId, ok()))
                .isInstanceOf(ForbiddenException.class);
        assertThat(returnRepository.count()).isZero();
    }

    @Test
    @DisplayName("a booking that never started cannot be returned")
    void confirmedButNotActiveCannotBeReturned() {
        pay();   // CONFIRMED, but the activation worker hasn't run

        assertThatThrownBy(() -> settlementService.recordReturn(bookingId, ownerId, ok()))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(returnRepository.count()).isZero();
    }

    @Test
    @DisplayName("an owner cannot claim more than the deposit, or claim on an OK return")
    void invalidClaimsAreRejected() {
        payAndActivate();

        assertThatThrownBy(() -> settlementService.recordReturn(bookingId, ownerId,
                new RecordReturnRequest(ReturnCondition.DAMAGED, new BigDecimal("9000.00"), "x")))
                .isInstanceOf(InvalidRequestException.class);

        assertThatThrownBy(() -> settlementService.recordReturn(bookingId, ownerId,
                new RecordReturnRequest(ReturnCondition.OK, new BigDecimal("100.00"), null)))
                .isInstanceOf(InvalidRequestException.class);

        // Nothing written by either attempt.
        assertThat(returnRepository.count()).isZero();
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).hasSize(4);
        assertThat(reloadBooking().getStatus()).isEqualTo(BookingStatus.ACTIVE);
    }

    // ------------------------------------------------------------------------- helpers

    private void pay() {
        paymentService.pay(bookingId, renterId, "key-1");
        deliver(succeeded("evt_fee", refOf(PaymentType.FEE)));
        deliver(succeeded("evt_dep", refOf(PaymentType.DEPOSIT)));
        assertThat(reloadBooking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    private void payAndActivate() {
        pay();
        assertThat(activationWorker.activateDue()).isEqualTo(1);
        assertThat(reloadBooking().getStatus()).isEqualTo(BookingStatus.ACTIVE);
    }

    private static RecordReturnRequest ok() {
        return new RecordReturnRequest(ReturnCondition.OK, null, null);
    }

    private void deliver(String payload) {
        webhookService.handle(payload, fakeGateway.signatureHeader(payload, Instant.now()));
    }

    private static String succeeded(String eventId, String gatewayRef) {
        return """
                {"id":"%s","type":"payment_intent.succeeded","data":{"object":{"id":"%s"}}}"""
                .formatted(eventId, gatewayRef);
    }

    private String refOf(PaymentType type) {
        return paymentRepository.findByBookingIdOrderByIdAsc(bookingId).stream()
                .filter(p -> p.getType() == type)
                .findFirst()
                .map(Payment::getGatewayRef)
                .orElseThrow(() -> new AssertionError("no " + type + " payment"));
    }

    private Booking reloadBooking() {
        return bookingRepository.findById(bookingId).orElseThrow();
    }

    private BigDecimal sumCredit(LedgerAccount account) {
        return ledgerRepository.findByBookingIdOrderByIdAsc(bookingId).stream()
                .filter(e -> e.getAccount() == account)
                .map(LedgerEntry::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Credits minus debits for one account — zero means everything that came in went out. */
    private BigDecimal net(LedgerAccount account) {
        return ledgerRepository.findByBookingIdOrderByIdAsc(bookingId).stream()
                .filter(e -> e.getAccount() == account)
                .map(e -> e.getCredit().subtract(e.getDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
