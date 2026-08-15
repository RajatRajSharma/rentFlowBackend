package com.rentflow.payment;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingService;
import com.rentflow.booking.BookingStatus;
import com.rentflow.booking.dto.CreateBookingRequest;
import com.rentflow.common.exception.IllegalTransitionException;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.ledger.LedgerRepository;
import com.rentflow.payment.dto.PaymentIntentResponse;
import com.rentflow.payment.dto.PaymentResponse;
import com.rentflow.payment.gateway.FakeGateway;
import com.rentflow.payment.gateway.PaymentGatewayException;
import com.rentflow.support.ControllableGateway;
import com.rentflow.support.IntegrationTestBase;
import com.rentflow.user.Role;
import com.rentflow.user.User;
import com.rentflow.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 15: what happens when the money doesn't move. A gateway outage must leave a
 * recoverable payment, and a declined card must leave nothing half-written.
 */
@DisplayName("Payment failure scenarios")
@Import(ControllableGateway.Config.class)
class PaymentFailureIT extends IntegrationTestBase {

    private static final BigDecimal DAILY_RATE = new BigDecimal("1000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("5000.00");
    private static final BigDecimal FEE = new BigDecimal("2000.00");

    @Autowired private PaymentService paymentService;
    @Autowired private WebhookService webhookService;
    @Autowired private BookingService bookingService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ControllableGateway gateway;
    @Autowired private FakeGateway fakeGateway;

    private Long bookingId;
    private Long renterId;
    private Long itemId;
    private LocalDate start;

    @BeforeEach
    void seed() {
        gateway.healIntents();

        User owner = userRepository.save(new User("Owner", "owner@test.com", "x", Role.USER));
        User renter = userRepository.save(new User("Renter", "renter@test.com", "x", Role.USER));
        Item item = itemRepository.save(new Item(
                owner.getId(), "Sony A7 III", "2 batteries", DAILY_RATE, DEPOSIT));

        start = LocalDate.now().plusDays(10);
        Booking booking = bookingRepository.save(new Booking(
                item.getId(), renter.getId(), start, start.plusDays(1), FEE, DEPOSIT));

        bookingId = booking.getId();
        renterId = renter.getId();
        itemId = item.getId();
    }

    @AfterEach
    void healGateway() {
        gateway.healIntents();
    }

    @Test
    @DisplayName("a gateway outage still leaves the charges reserved, and a retry recovers them")
    void gatewayOutageIsRecoverable() {
        gateway.breakIntents("connection reset");

        assertThatThrownBy(() -> paymentService.pay(bookingId, renterId, "key-1"))
                .isInstanceOf(PaymentGatewayException.class);

        // Reserved before the call went out, so the keys are claimed even though it failed.
        // The other ordering — charge first, write after — loses the money's only record.
        List<Payment> reserved = paymentRepository.findByBookingIdOrderByIdAsc(bookingId);
        assertThat(reserved).hasSize(2);
        assertThat(reserved).extracting(Payment::getStatus).containsOnly(PaymentStatus.PENDING);
        assertThat(reserved).allSatisfy(p -> assertThat(p.getGatewayRef()).isNull());
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).isEmpty();

        gateway.healIntents();
        PaymentIntentResponse retry = paymentService.pay(bookingId, renterId, "key-1");

        // Same two rows, now with refs — not a second set of charges.
        assertThat(retry.payments()).extracting(PaymentResponse::id)
                .isEqualTo(reserved.stream().map(Payment::getId).toList());
        assertThat(retry.payments()).allSatisfy(p -> assertThat(p.gatewayRef()).isNotBlank());
        assertThat(paymentRepository.findByBookingIdOrderByIdAsc(bookingId)).hasSize(2);
    }

    @Test
    @DisplayName("a gateway that never answers does NOT mark the payment failed")
    void timeoutIsNotTreatedAsFailure() {
        // A timeout means "unknown", not "declined" — the charge may well have gone through
        // at the gateway. Only the gateway telling us so may settle a payment.
        gateway.breakIntents("read timed out");

        assertThatThrownBy(() -> paymentService.pay(bookingId, renterId, "key-1"))
                .isInstanceOf(PaymentGatewayException.class);

        assertThat(paymentRepository.findByBookingIdOrderByIdAsc(bookingId))
                .extracting(Payment::getStatus)
                .containsOnly(PaymentStatus.PENDING);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("a declined card rolls back cleanly and frees the dates")
    void declineRollsBackCleanly() {
        paymentService.pay(bookingId, renterId, "key-1");
        Payment fee = paymentOfType(PaymentType.FEE);

        deliver(failedEvent("evt_declined", fee.getGatewayRef(), "card_declined"));

        Payment settled = paymentRepository.findById(fee.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(settled.getFailureReason()).isEqualTo("card_declined");

        // Nothing half-written: no ledger movement for money that never moved.
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).isEmpty();
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PAYMENT_FAILED);

        // The dates are genuinely free — the exclusion constraint no longer sees this
        // booking, so someone else can take the same item on the same days.
        Booking replacement = bookingService.create(
                new CreateBookingRequest(itemId, start, start.plusDays(1)), renterId);
        assertThat(replacement.getId()).isNotEqualTo(bookingId);
    }

    @Test
    @DisplayName("a dead booking cannot be paid for again")
    void failedBookingCannotBeRetried() {
        paymentService.pay(bookingId, renterId, "key-1");
        deliver(failedEvent("evt_declined", paymentOfType(PaymentType.FEE).getGatewayRef(), "card_declined"));

        int callsBefore = gateway.intentCalls();

        // PAYMENT_FAILED is terminal: the answer is a 409, and the gateway is never touched.
        assertThatThrownBy(() -> paymentService.pay(bookingId, renterId, "key-2"))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(gateway.intentCalls()).isEqualTo(callsBefore);
        assertThat(paymentRepository.findByBookingIdOrderByIdAsc(bookingId)).hasSize(2);
    }

    // ------------------------------------------------------------------------- helpers

    private void deliver(String payload) {
        webhookService.handle(payload, fakeGateway.signatureHeader(payload, Instant.now()));
    }

    private static String failedEvent(String eventId, String gatewayRef, String code) {
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
}
