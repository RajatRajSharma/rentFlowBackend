package com.rentflow.payment;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStatus;
import com.rentflow.common.exception.ForbiddenException;
import com.rentflow.common.exception.IllegalTransitionException;
import com.rentflow.common.exception.InvalidRequestException;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.payment.dto.PaymentIntentResponse;
import com.rentflow.payment.dto.PaymentResponse;
import com.rentflow.support.IntegrationTestBase;
import com.rentflow.user.Role;
import com.rentflow.user.User;
import com.rentflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The money equivalent of {@code BookingConcurrencyIT}: hammer the pay endpoint and prove
 * the renter is only ever charged once.
 *
 * <p>Note what is deliberately NOT asserted: that the gateway was called exactly once.
 * By design it is called on every attempt, carrying the same Idempotency-Key, so the
 * caller always gets back a usable client secret — and it is the gateway's own
 * idempotency that makes those repeat calls free. What must be exactly-once is <em>our
 * record of what is owed</em>, and that is what these tests pin down.
 */
@DisplayName("Paying for a booking is idempotent")
class PaymentIdempotencyIT extends IntegrationTestBase {

    private static final int CONCURRENT_REQUESTS = 100;
    private static final int POOL_SIZE = 32;

    private static final BigDecimal DAILY_RATE = new BigDecimal("1000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("5000.00");

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserRepository userRepository;

    private Long bookingId;
    private Long renterId;
    private Long ownerId;

    @BeforeEach
    void seed() {
        // No cleanup here — IntegrationTestBase truncates every table before each test.
        User owner = userRepository.save(new User("Owner", "owner@test.com", "x", Role.USER));
        User renter = userRepository.save(new User("Renter", "renter@test.com", "x", Role.USER));
        Item item = itemRepository.save(new Item(
                owner.getId(), "Sony A7 III", "2 batteries", DAILY_RATE, DEPOSIT));

        LocalDate start = LocalDate.now().plusDays(10);
        Booking booking = bookingRepository.save(new Booking(
                item.getId(), renter.getId(), start, start.plusDays(1),  // 2 billable days
                DAILY_RATE.multiply(BigDecimal.valueOf(2)), DEPOSIT));

        bookingId = booking.getId();
        renterId = renter.getId();
        ownerId = owner.getId();
    }

    @Test
    @DisplayName("one call creates a fee and a deposit, both PENDING")
    void createsFeeAndDeposit() {
        PaymentIntentResponse response = paymentService.pay(bookingId, renterId, "key-1");

        assertThat(response.payments()).hasSize(2);
        assertThat(response.totalCharged()).isEqualByComparingTo("7000.00");   // 2000 fee + 5000 deposit
        assertThat(response.bookingStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);

        assertThat(response.payments())
                .extracting(PaymentResponse::type)
                .containsExactly(PaymentType.FEE, PaymentType.DEPOSIT);

        // Nothing is SUCCEEDED yet — creating an intent is not being paid. Only the
        // webhook gets to say the money moved.
        assertThat(response.payments())
                .extracting(PaymentResponse::status)
                .containsOnly(PaymentStatus.PENDING);

        // Every line carries a gateway ref and a secret the client can actually pay with.
        assertThat(response.payments())
                .allSatisfy(p -> {
                    assertThat(p.gatewayRef()).isNotBlank();
                    assertThat(p.clientSecret()).isNotBlank();
                });
    }

    @Test
    @DisplayName("100 simultaneous retries of the same key -> exactly 2 payment rows")
    void concurrentRetriesOfOneKeyChargeOnce() throws Exception {
        ConcurrentLinkedQueue<PaymentIntentResponse> responses = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        CountDownLatch startGun = new CountDownLatch(1);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            pool.submit(() -> {
                startGun.await();
                try {
                    responses.add(paymentService.pay(bookingId, renterId, "same-key"));
                } catch (Exception ex) {
                    failures.incrementAndGet();
                    System.out.println("unexpected: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
                }
                return null;
            });
        }
        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();

        List<Payment> stored = paymentRepository.findByBookingIdOrderByIdAsc(bookingId);

        // Every caller must have been handed the SAME two charges, not just the same count.
        Set<Long> distinctPaymentIds = responses.stream()
                .flatMap(r -> r.payments().stream())
                .map(PaymentResponse::id)
                .collect(Collectors.toSet());

        System.out.printf("""

                ================= PAYMENT IDEMPOTENCY PROOF =================
                 pay requests fired  : %d
                 responses returned  : %d
                 failures            : %d
                 payment rows in db  : %d
                 distinct payment ids: %d
                 total charged       : %s
                =============================================================
                %n""",
                CONCURRENT_REQUESTS, responses.size(), failures.get(), stored.size(),
                distinctPaymentIds.size(), stored.stream()
                        .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));

        assertThat(failures).as("no request should fail").hasValue(0);
        assertThat(responses).hasSize(CONCURRENT_REQUESTS);
        assertThat(stored).as("one fee + one deposit, however many retries").hasSize(2);
        assertThat(distinctPaymentIds).as("every caller saw the same two charges").hasSize(2);
        assertThat(stored.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("7000.00");
    }

    @Test
    @DisplayName("a retry returns the identical charges, not new ones")
    void repeatedCallWithSameKeyReplays() {
        PaymentIntentResponse first = paymentService.pay(bookingId, renterId, "key-1");
        PaymentIntentResponse second = paymentService.pay(bookingId, renterId, "key-1");

        assertThat(second.payments()).extracting(PaymentResponse::id)
                .isEqualTo(first.payments().stream().map(PaymentResponse::id).toList());
        // The gateway replays its intent for a repeated key, so the client can still pay.
        assertThat(second.payments()).extracting(PaymentResponse::gatewayRef)
                .isEqualTo(first.payments().stream().map(PaymentResponse::gatewayRef).toList());
        assertThat(paymentRepository.findByBookingIdOrderByIdAsc(bookingId)).hasSize(2);
    }

    @Test
    @DisplayName("a DIFFERENT key on the same booking still does not double-charge")
    void differentKeySameBookingReusesTheLiveCharges() {
        PaymentIntentResponse first = paymentService.pay(bookingId, renterId, "key-1");
        // The renter reopens the app, the client mints a fresh key, and tries again.
        // Key-level idempotency cannot catch this — the keys differ, nothing collides.
        PaymentIntentResponse second = paymentService.pay(bookingId, renterId, "key-2");

        assertThat(second.payments()).extracting(PaymentResponse::id)
                .isEqualTo(first.payments().stream().map(PaymentResponse::id).toList());
        assertThat(paymentRepository.findByBookingIdOrderByIdAsc(bookingId))
                .as("a booking has one live set of charges, whatever key asks for them")
                .hasSize(2);
    }

    @Test
    @DisplayName("only the renter can pay; the owner cannot")
    void ownerCannotPay() {
        assertThatThrownBy(() -> paymentService.pay(bookingId, ownerId, "key-1"))
                .isInstanceOf(ForbiddenException.class);
        assertThat(paymentRepository.findByBookingIdOrderByIdAsc(bookingId)).isEmpty();
    }

    @Test
    @DisplayName("a blank idempotency key is rejected before anything is written")
    void blankKeyRejected() {
        assertThatThrownBy(() -> paymentService.pay(bookingId, renterId, "  "))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(paymentRepository.findByBookingIdOrderByIdAsc(bookingId)).isEmpty();
    }

    @Test
    @DisplayName("a cancelled booking cannot be paid for")
    void cancelledBookingCannotBePaid() {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);

        assertThatThrownBy(() -> paymentService.pay(bookingId, renterId, "key-1"))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(paymentRepository.findByBookingIdOrderByIdAsc(bookingId)).isEmpty();
    }

    @Test
    @DisplayName("a replay still works after the booking is CONFIRMED")
    void replayAfterConfirmationStillReturnsTheCharges() {
        PaymentIntentResponse first = paymentService.pay(bookingId, renterId, "key-1");

        // The webhook lands and confirms the booking...
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);

        // ...and only then does the client's retry arrive. It must get its intents back,
        // not a 409 about a booking that can no longer be paid for — the status check
        // gates CREATING charges, not REPLAYING them.
        PaymentIntentResponse replay = paymentService.pay(bookingId, renterId, "key-1");

        assertThat(replay.payments()).extracting(PaymentResponse::id)
                .isEqualTo(first.payments().stream().map(PaymentResponse::id).toList());
        assertThat(replay.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }
}
