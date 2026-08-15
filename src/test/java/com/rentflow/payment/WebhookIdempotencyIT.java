package com.rentflow.payment;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStatus;
import com.rentflow.common.exception.NotFoundException;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.ledger.LedgerRepository;
import com.rentflow.payment.gateway.FakeGateway;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 15: the webhook dedupe under the conditions gateways actually create — the same event
 * delivered twice at once, and a delivery that our own code fails halfway through.
 */
@DisplayName("Webhook delivery is exactly-once")
class WebhookIdempotencyIT extends IntegrationTestBase {

    private static final int SIMULTANEOUS_DELIVERIES = 8;

    private static final BigDecimal DAILY_RATE = new BigDecimal("1000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("5000.00");
    private static final BigDecimal FEE = new BigDecimal("2000.00");

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

    @BeforeEach
    void seed() {
        User owner = userRepository.save(new User("Owner", "owner@test.com", "x", Role.USER));
        User renter = userRepository.save(new User("Renter", "renter@test.com", "x", Role.USER));
        Item item = itemRepository.save(new Item(
                owner.getId(), "Sony A7 III", "2 batteries", DAILY_RATE, DEPOSIT));

        LocalDate start = LocalDate.now().plusDays(10);
        Booking booking = bookingRepository.save(new Booking(
                item.getId(), renter.getId(), start, start.plusDays(1), FEE, DEPOSIT));

        bookingId = booking.getId();
        paymentService.pay(bookingId, renter.getId(), "key-1");
    }

    @Test
    @DisplayName("8 simultaneous copies of one event -> one ledger movement")
    void simultaneousDuplicatesPostOnce() throws Exception {
        String payload = succeeded("evt_fee", refOf(PaymentType.FEE));
        String signature = fakeGateway.signatureHeader(payload, Instant.now());

        ConcurrentLinkedQueue<WebhookResult> results = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(SIMULTANEOUS_DELIVERIES);
        CountDownLatch startGun = new CountDownLatch(1);

        for (int i = 0; i < SIMULTANEOUS_DELIVERIES; i++) {
            pool.submit(() -> {
                startGun.await();
                try {
                    results.add(webhookService.handle(payload, signature));
                } catch (Exception ex) {
                    errors.incrementAndGet();
                    System.out.println("unexpected: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
                }
                return null;
            });
        }
        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();

        long processed = results.stream().filter(r -> r == WebhookResult.PROCESSED).count();
        long duplicates = results.stream().filter(r -> r == WebhookResult.DUPLICATE).count();

        System.out.printf("""

                =============== WEBHOOK IDEMPOTENCY PROOF ===============
                 deliveries fired : %d
                 processed        : %d
                 duplicates       : %d
                 errors           : %d
                 ledger entries   : %d
                =========================================================
                %n""",
                SIMULTANEOUS_DELIVERIES, processed, duplicates, errors.get(),
                ledgerRepository.findByBookingIdOrderByIdAsc(bookingId).size());

        assertThat(errors).hasValue(0);
        assertThat(processed).as("exactly one delivery may do the work").isEqualTo(1);
        assertThat(duplicates).isEqualTo(SIMULTANEOUS_DELIVERIES - 1);

        // The whole point: a second movement here would silently unbalance the books.
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).hasSize(2);
        assertThat(ledgerRepository.balanceFor(bookingId).isBalanced()).isTrue();
        assertThat(processedWebhookRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a delivery that fails halfway releases its claim, so the retry works")
    void aFailedDeliveryDoesNotBurnTheEventId() {
        // Our own 500: the event is claimed, then the work throws. If the claim survived,
        // the gateway's retry would be answered "already processed" for work never done.
        String orphan = succeeded("evt_fee", "pi_never_seen");
        assertThatThrownBy(() -> deliver(orphan)).isInstanceOf(NotFoundException.class);
        assertThat(processedWebhookRepository.count())
                .as("the claim must roll back with the work it claimed")
                .isZero();

        // The gateway redelivers the same event id, and this time it resolves.
        assertThat(deliver(succeeded("evt_fee", refOf(PaymentType.FEE)))).isEqualTo(WebhookResult.PROCESSED);
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).hasSize(2);
        assertThat(processedWebhookRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("fee and deposit events arriving together confirm the booking exactly once")
    void concurrentDistinctEventsConfirmOnce() throws Exception {
        String fee = succeeded("evt_fee", refOf(PaymentType.FEE));
        String deposit = succeeded("evt_dep", refOf(PaymentType.DEPOSIT));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (String payload : List.of(fee, deposit)) {
            pool.submit(() -> {
                startGun.await();
                try {
                    deliver(payload);
                } catch (Exception ex) {
                    errors.incrementAndGet();
                    System.out.println("unexpected: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
                }
                return null;
            });
        }
        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();

        // Both events are real work, so both must land — the booking row lock is what stops
        // them each deciding "everything has cleared" from a stale read.
        assertThat(errors).hasValue(0);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(ledgerRepository.findByBookingIdOrderByIdAsc(bookingId)).hasSize(4);
        assertThat(ledgerRepository.balanceFor(bookingId).isBalanced()).isTrue();
    }

    @Test
    @DisplayName("the deposit clearing before the fee still confirms only when both are in")
    void outOfOrderDeliveriesStillWait() {
        deliver(succeeded("evt_dep", refOf(PaymentType.DEPOSIT)));
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);

        deliver(succeeded("evt_fee", refOf(PaymentType.FEE)));
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(ledgerRepository.balanceFor(bookingId).totalDebit()).isEqualByComparingTo("7000.00");
    }

    // ------------------------------------------------------------------------- helpers

    private WebhookResult deliver(String payload) {
        return webhookService.handle(payload, fakeGateway.signatureHeader(payload, Instant.now()));
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
                .orElseThrow(() -> new AssertionError("no " + type + " payment"))
                .getGatewayRef();
    }
}
