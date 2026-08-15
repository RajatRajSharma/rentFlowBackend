package com.rentflow.payment;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStatus;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.ledger.LedgerAccount;
import com.rentflow.ledger.LedgerBalance;
import com.rentflow.ledger.LedgerEntry;
import com.rentflow.ledger.LedgerRepository;
import com.rentflow.payment.gateway.FakeGateway;
import com.rentflow.payment.gateway.WebhookSignatureException;
import com.rentflow.support.IntegrationTestBase;
import com.rentflow.user.Role;
import com.rentflow.user.User;
import com.rentflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 13 + 14: a webhook resolves a payment, writes a balanced ledger movement, and confirms
 * the booking — and a duplicate delivery of the same event changes nothing.
 *
 * The duplicate case is the one that matters most. Every gateway delivers at-least-once, so
 * "we got this event twice" is not an edge case, it is Tuesday. Without the dedupe the second
 * delivery writes a second set of ledger entries and the books silently stop balancing.
 */
@DisplayName("Payment webhooks")
class PaymentWebhookIT extends IntegrationTestBase {

    private static final BigDecimal DAILY_RATE = new BigDecimal("1000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("5000.00");
    private static final BigDecimal FEE = new BigDecimal("2000.00");   // 2 billable days

    @Autowired private PaymentService paymentService;
    @Autowired private WebhookService webhookService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProcessedWebhookRepository processedWebhookRepository;
    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FakeGateway fakeGateway;

    private Long bookingId;
    private Long renterId;

    @BeforeEach
    void seed() {
        // No cleanup here — IntegrationTestBase truncates every table before each test.
        User owner = userRepository.save(new User("Owner", "owner@test.com", "x", Role.USER));
        User renter = userRepository.save(new User("Renter", "renter@test.com", "x", Role.USER));
        Item item = itemRepository.save(new Item(
                owner.getId(), "Sony A7 III", "2 batteries", DAILY_RATE, DEPOSIT));

        LocalDate start = LocalDate.now().plusDays(10);
        Booking booking = bookingRepository.save(new Booking(
                item.getId(), renter.getId(), start, start.plusDays(1), FEE, DEPOSIT));

        bookingId = booking.getId();
        renterId = renter.getId();

        // Create the intents, so there are real payments for the webhooks to resolve.
        paymentService.pay(bookingId, renterId, "key-1");
    }

    @Test
    @DisplayName("fee success writes a balanced pair but does NOT confirm the booking yet")
    void feeSuccessPostsLedgerButWaitsForTheDeposit() {
        Payment fee = paymentOfType(PaymentType.FEE);

        WebhookResult result = deliver(succeeded("evt_fee", fee.getGatewayRef()));

        assertThat(result).isEqualTo(WebhookResult.PROCESSED);
        assertThat(reload(fee).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        // Fee paid: RENTER_CASH debit 2000 | OWNER_PAYABLE credit 2000
        List<LedgerEntry> entries = ledgerRepository.findByBookingIdOrderByIdAsc(bookingId);
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LedgerEntry::getAccount)
                .containsExactly(LedgerAccount.RENTER_CASH, LedgerAccount.OWNER_PAYABLE);
        assertThat(entries.get(0).getDebit()).isEqualByComparingTo(FEE);
        assertThat(entries.get(1).getCredit()).isEqualByComparingTo(FEE);
        assertThat(ledgerRepository.balanceFor(bookingId).isBalanced()).isTrue();

        // The deposit hasn't cleared, so the item must NOT be handed over yet.
        assertThat(reloadBooking().getStatus())
                .as("confirming on the fee alone would hand over an item with no deposit taken")
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("both payments cleared -> booking CONFIRMED and the books balance")
    void bothPaymentsClearedConfirmsTheBooking() {
        deliver(succeeded("evt_fee", paymentOfType(PaymentType.FEE).getGatewayRef()));
        deliver(succeeded("evt_dep", paymentOfType(PaymentType.DEPOSIT).getGatewayRef()));

        assertThat(reloadBooking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        List<LedgerEntry> entries = ledgerRepository.findByBookingIdOrderByIdAsc(bookingId);
        LedgerBalance balance = ledgerRepository.balanceFor(bookingId);

        System.out.printf("""

                ==================== LEDGER (booking %d) ====================
                %s
                 total debit : %s
                 total credit: %s
                 balanced    : %s
                ============================================================
                %n""",
                bookingId,
                entries.stream()
                        .map(e -> String.format(" %-14s debit %8s  credit %8s",
                                e.getAccount(), e.getDebit(), e.getCredit()))
                        .reduce((a, b) -> a + "%n".formatted() + b).orElse(""),
                balance.totalDebit(), balance.totalCredit(), balance.isBalanced());

        // The worked example from docs/README.md §11: fee 2000 + deposit 5000.
        assertThat(entries).hasSize(4);
        assertThat(balance.totalDebit()).isEqualByComparingTo("7000.00");
        assertThat(balance.totalCredit()).isEqualByComparingTo("7000.00");
        assertThat(balance.isBalanced()).isTrue();
        assertThat(balance.drift()).isEqualByComparingTo(BigDecimal.ZERO);

        // And the two credits land in DIFFERENT accounts — that separation is the whole
        // point. Both are cash we hold; only OWNER_PAYABLE is ours to pay out.
        assertThat(sumCredit(LedgerAccount.OWNER_PAYABLE)).isEqualByComparingTo(FEE);
        assertThat(sumCredit(LedgerAccount.DEPOSIT_HELD)).isEqualByComparingTo(DEPOSIT);
    }

    @Test
    @DisplayName("a duplicate delivery is a no-op — no second ledger entries")
    void duplicateDeliveryChangesNothing() {
        String payload = succeeded("evt_fee", paymentOfType(PaymentType.FEE).getGatewayRef());

        assertThat(deliver(payload)).isEqualTo(WebhookResult.PROCESSED);
        List<LedgerEntry> afterFirst = ledgerRepository.findByBookingIdOrderByIdAsc(bookingId);

        // The gateway redelivers — same event id, same payload, valid signature.
        assertThat(deliver(payload)).isEqualTo(WebhookResult.DUPLICATE);
        assertThat(deliver(payload)).isEqualTo(WebhookResult.DUPLICATE);

        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId))
                .as("a redelivered event must not post the movement again")
                .hasSize(afterFirst.size());
        assertThat(ledgerRepository.balanceFor(bookingId).isBalanced()).isTrue();
        assertThat(processedWebhookRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("payment failure releases the dates")
    void failureReleasesTheDates() {
        Payment fee = paymentOfType(PaymentType.FEE);

        assertThat(deliver(failed("evt_fail", fee.getGatewayRef(), "card_declined")))
                .isEqualTo(WebhookResult.PROCESSED);

        Payment reloaded = reload(fee);
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("card_declined");

        Booking booking = reloadBooking();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(booking.getStatus().blocksDates())
                .as("a failed booking must stop holding the dates")
                .isFalse();

        // No money moved, so nothing is recorded. The ledger tracks what HAPPENED, not what
        // was attempted — that is what the payments table is for.
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).isEmpty();

        // And the dates really are free: the overlap query no longer sees this booking.
        assertThat(bookingRepository.findOverlapping(
                booking.getItemId(), BookingStatus.BLOCKING,
                booking.getStartDate(), booking.getEndDate())).isEmpty();
    }

    @Test
    @DisplayName("an unsigned or wrongly-signed payload is rejected")
    void badSignatureIsRejected() {
        String payload = succeeded("evt_fee", paymentOfType(PaymentType.FEE).getGatewayRef());

        assertThatThrownBy(() -> webhookService.handle(payload, null))
                .isInstanceOf(WebhookSignatureException.class);
        assertThatThrownBy(() -> webhookService.handle(payload, "t=123,v1=deadbeef"))
                .isInstanceOf(WebhookSignatureException.class);
        assertThatThrownBy(() -> webhookService.handle(payload, "garbage"))
                .isInstanceOf(WebhookSignatureException.class);

        // Rejected before anything was touched.
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).isEmpty();
        assertThat(processedWebhookRepository.count()).isZero();
        assertThat(reloadBooking().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("a valid signature with a stale timestamp is rejected as a replay")
    void staleTimestampIsRejected() {
        String payload = succeeded("evt_fee", paymentOfType(PaymentType.FEE).getGatewayRef());

        // Genuinely signed — just captured an hour ago. The tolerance window is what stops a
        // recorded request being replayed forever.
        String staleHeader = fakeGateway.signatureHeader(payload, Instant.now().minus(Duration.ofHours(1)));

        assertThatThrownBy(() -> webhookService.handle(payload, staleHeader))
                .isInstanceOf(WebhookSignatureException.class);
        assertThat(processedWebhookRepository.count()).isZero();
    }

    @Test
    @DisplayName("an event type we don't act on is accepted and ignored")
    void unknownEventTypeIsIgnored() {
        String payload = """
                {"id":"evt_other","type":"charge.updated","data":{"object":{"id":"ch_1"}}}""";

        assertThat(deliver(payload)).isEqualTo(WebhookResult.IGNORED);
        // Not recorded: there is nothing to be idempotent about, and storing every event we
        // ignore would grow the table for no benefit.
        assertThat(processedWebhookRepository.count()).isZero();
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    /** Signs the payload the way the gateway would, then hands it to the service. */
    private WebhookResult deliver(String payload) {
        return webhookService.handle(payload, fakeGateway.signatureHeader(payload, Instant.now()));
    }

    private static String succeeded(String eventId, String gatewayRef) {
        return """
                {"id":"%s","type":"payment_intent.succeeded","data":{"object":{"id":"%s"}}}"""
                .formatted(eventId, gatewayRef);
    }

    private static String failed(String eventId, String gatewayRef, String code) {
        return """
                {"id":"%s","type":"payment_intent.payment_failed","data":{"object":{\
                "id":"%s","last_payment_error":{"code":"%s"}}}}"""
                .formatted(eventId, gatewayRef, code);
    }

    private Payment paymentOfType(PaymentType type) {
        return paymentRepository.findByBookingIdOrderByIdAsc(bookingId).stream()
                .filter(p -> p.getType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " payment was created"));
    }

    private Payment reload(Payment payment) {
        return paymentRepository.findById(payment.getId()).orElseThrow();
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
}
