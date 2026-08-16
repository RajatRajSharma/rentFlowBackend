package com.rentflow.payment;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStatus;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.ledger.LedgerRepository;
import com.rentflow.payment.gateway.GatewayPaymentStatus;
import com.rentflow.payment.gateway.PaymentGatewayException;
import com.rentflow.support.ControllableGateway;
import com.rentflow.support.IntegrationTestBase;
import com.rentflow.user.Role;
import com.rentflow.user.User;
import com.rentflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 18: the webhook never arrived. The renter has been charged, and without this sweep the
 * booking sits PENDING_PAYMENT forever, holding dates nobody can use.
 */
@DisplayName("Reconciliation of stranded payments")
@Import(ControllableGateway.Config.class)
class ReconciliationWorkerIT extends IntegrationTestBase {

    private static final BigDecimal DAILY_RATE = new BigDecimal("1000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("5000.00");
    private static final BigDecimal FEE = new BigDecimal("2000.00");

    @Autowired private ReconciliationWorker worker;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ControllableGateway gateway;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long bookingId;
    private Long renterId;

    @BeforeEach
    void seed() {
        gateway.reset();

        User owner = userRepository.save(new User("Owner", "owner@test.com", "x", Role.USER));
        User renter = userRepository.save(new User("Renter", "renter@test.com", "x", Role.USER));
        Item item = itemRepository.save(new Item(
                owner.getId(), "Sony A7 III", "2 batteries", DAILY_RATE, DEPOSIT));

        LocalDate start = LocalDate.now().plusDays(10);
        Booking booking = bookingRepository.save(new Booking(
                item.getId(), renter.getId(), start, start.plusDays(1), FEE, DEPOSIT));

        bookingId = booking.getId();
        renterId = renter.getId();
    }

    @Test
    @DisplayName("a payment the gateway says succeeded is settled, and the booking confirms")
    void settlesWhatTheGatewayConfirms() {
        paymentService.pay(bookingId, renterId, "key-1");
        payments().forEach(p -> gateway.stageStatus(p.getGatewayRef(), GatewayPaymentStatus.SUCCEEDED));
        ageAllPayments("1 hour");

        ReconciliationWorker.Summary summary = worker.sweep();

        System.out.printf("%n=========== RECONCILIATION ===========%n %s%n======================================%n%n",
                summary);

        assertThat(summary.examined()).isEqualTo(2);
        assertThat(summary.settled()).isEqualTo(2);
        assertThat(summary.unresolvable()).isZero();

        assertThat(payments()).extracting(Payment::getStatus).containsOnly(PaymentStatus.SUCCEEDED);
        assertThat(reloadBooking().getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        // The same ledger the webhook would have written — one settlement path, not two.
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).hasSize(4);
        assertThat(ledgerRepository.balanceFor(bookingId).isBalanced()).isTrue();
    }

    @Test
    @DisplayName("a payment the gateway says failed releases the dates")
    void failsWhatTheGatewayRejected() {
        paymentService.pay(bookingId, renterId, "key-1");
        gateway.stageStatus(paymentOf(PaymentType.FEE).getGatewayRef(), GatewayPaymentStatus.FAILED);
        ageAllPayments("1 hour");

        ReconciliationWorker.Summary summary = worker.sweep();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(paymentRepository.findById(paymentOf(PaymentType.FEE).getId()).orElseThrow()
                .getFailureReason()).contains("reconciled");
        assertThat(reloadBooking().getStatus()).isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).isEmpty();
    }

    @Test
    @DisplayName("still-pending and unknown answers change nothing")
    void neverGuessesAnOutcome() {
        paymentService.pay(bookingId, renterId, "key-1");
        gateway.stageStatus(paymentOf(PaymentType.FEE).getGatewayRef(), GatewayPaymentStatus.PENDING);
        gateway.stageStatus(paymentOf(PaymentType.DEPOSIT).getGatewayRef(), GatewayPaymentStatus.UNKNOWN);
        ageAllPayments("1 hour");

        ReconciliationWorker.Summary summary = worker.sweep();

        assertThat(summary.settled()).isZero();
        assertThat(summary.failed()).isZero();
        assertThat(summary.stillPending()).isEqualTo(1);
        assertThat(summary.unresolvable()).isEqualTo(1);
        assertThat(payments()).extracting(Payment::getStatus).containsOnly(PaymentStatus.PENDING);
        assertThat(reloadBooking().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("a payment with no gateway ref is flagged, never failed")
    void refLessPaymentsNeedAHuman() {
        // The Day 15 timeout: reserved, then the gateway call never came back with a ref.
        gateway.breakIntents("read timed out");
        assertThatThrownBy(() -> paymentService.pay(bookingId, renterId, "key-1"))
                .isInstanceOf(PaymentGatewayException.class);
        gateway.healIntents();
        ageAllPayments("1 hour");

        ReconciliationWorker.Summary summary = worker.sweep();

        assertThat(summary.unresolvable()).isEqualTo(2);
        assertThat(gateway.statusCalls())
                .as("there is nothing to ask about without a reference")
                .isZero();
        assertThat(payments()).extracting(Payment::getStatus)
                .as("an intent we lost track of may still exist — guessing FAILED could strand a real charge")
                .containsOnly(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("payments made moments ago are left in flight")
    void freshPaymentsAreNotSwept() {
        paymentService.pay(bookingId, renterId, "key-1");
        payments().forEach(p -> gateway.stageStatus(p.getGatewayRef(), GatewayPaymentStatus.SUCCEEDED));

        ReconciliationWorker.Summary summary = worker.sweep();

        assertThat(summary.examined())
                .as("the customer may still be typing their card number")
                .isZero();
        assertThat(payments()).extracting(Payment::getStatus).containsOnly(PaymentStatus.PENDING);
    }

    // ------------------------------------------------------------------------- helpers

    /** Backdates the rows past the staleness cutoff, rather than sleeping 15 minutes. */
    private void ageAllPayments(String interval) {
        jdbcTemplate.update(
                "UPDATE payments SET created_at = now() - CAST(? AS interval) WHERE booking_id = ?",
                interval, bookingId);
    }

    private java.util.List<Payment> payments() {
        return paymentRepository.findByBookingIdOrderByIdAsc(bookingId);
    }

    private Payment paymentOf(PaymentType type) {
        return payments().stream()
                .filter(p -> p.getType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " payment"));
    }

    private Booking reloadBooking() {
        return bookingRepository.findById(bookingId).orElseThrow();
    }
}
