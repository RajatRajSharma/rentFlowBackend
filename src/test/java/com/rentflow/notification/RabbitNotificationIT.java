package com.rentflow.notification;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.event.RabbitConfig;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.payment.Payment;
import com.rentflow.payment.PaymentRepository;
import com.rentflow.payment.PaymentService;
import com.rentflow.payment.PaymentType;
import com.rentflow.payment.WebhookService;
import com.rentflow.payment.gateway.FakeGateway;
import com.rentflow.support.IntegrationTestBase;
import com.rentflow.user.Role;
import com.rentflow.user.User;
import com.rentflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 17: the whole async path — webhook commits, event goes to RabbitMQ, a consumer thread
 * turns it into email. Requires the compose broker, the same way the rest require Postgres.
 */
@DisplayName("Notifications over RabbitMQ")
class RabbitNotificationIT extends IntegrationTestBase {

    private static final BigDecimal DAILY_RATE = new BigDecimal("1000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("5000.00");
    private static final BigDecimal FEE = new BigDecimal("2000.00");

    @Autowired private PaymentService paymentService;
    @Autowired private WebhookService webhookService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailService emailService;
    @Autowired private FakeGateway fakeGateway;
    @Autowired private AmqpAdmin amqpAdmin;

    private Long bookingId;

    @BeforeEach
    void seed() {
        // Leftovers from an earlier run point at bookings this test just truncated away.
        amqpAdmin.purgeQueue(RabbitConfig.NOTIFICATIONS_QUEUE, false);
        emailService.clearOutbox();

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
    @DisplayName("a cleared fee emails the renter, off the request thread")
    void paymentSucceededEmailsTheRenter() {
        deliver(succeeded("evt_fee_" + bookingId, refOf(PaymentType.FEE)));

        List<EmailService.SentEmail> sent = await(() -> emailService.outbox(), 1);

        assertThat(sent).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo("renter@test.com");
            assertThat(email.subject()).contains("fee payment");
            assertThat(email.body()).contains("2000.00");
        });
    }

    @Test
    @DisplayName("confirmation emails BOTH parties")
    void bookingConfirmedEmailsBothSides() {
        deliver(succeeded("evt_fee_" + bookingId, refOf(PaymentType.FEE)));
        deliver(succeeded("evt_dep_" + bookingId, refOf(PaymentType.DEPOSIT)));

        // 2 payment emails to the renter + the confirmation pair.
        List<EmailService.SentEmail> sent = await(() -> emailService.outbox(), 4);

        assertThat(sent).filteredOn(e -> e.subject().equals("Your booking is confirmed"))
                .singleElement()
                .satisfies(e -> assertThat(e.to()).isEqualTo("renter@test.com"));
        assertThat(sent).filteredOn(e -> e.subject().equals("Your item is booked"))
                .singleElement()
                .satisfies(e -> assertThat(e.to()).isEqualTo("owner@test.com"));
    }

    @Test
    @DisplayName("a failed payment emails nobody")
    void failedPaymentEmailsNobody() {
        deliver(failed("evt_fail_" + bookingId, refOf(PaymentType.FEE)));

        // Nothing to wait for; give the consumer a moment to prove it stays quiet.
        assertThat(quietFor(Duration.ofSeconds(2))).isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    /** Polls until at least {@code atLeast} emails have landed, or the wait runs out. */
    private static List<EmailService.SentEmail> await(Supplier<List<EmailService.SentEmail>> outbox,
                                                      int atLeast) {
        long deadline = System.currentTimeMillis() + 15_000;
        List<EmailService.SentEmail> sent = outbox.get();
        while (sent.size() < atLeast && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
            sent = outbox.get();
        }
        assertThat(sent).as("the consumer should have emailed by now — is the broker up?")
                .hasSizeGreaterThanOrEqualTo(atLeast);
        return sent;
    }

    private List<EmailService.SentEmail> quietFor(Duration duration) {
        long deadline = System.currentTimeMillis() + duration.toMillis();
        while (System.currentTimeMillis() < deadline && emailService.outbox().isEmpty()) {
            Thread.onSpinWait();
        }
        return emailService.outbox();
    }

    private void deliver(String payload) {
        webhookService.handle(payload, fakeGateway.signatureHeader(payload, Instant.now()));
    }

    private static String succeeded(String eventId, String gatewayRef) {
        return """
                {"id":"%s","type":"payment_intent.succeeded","data":{"object":{"id":"%s"}}}"""
                .formatted(eventId, gatewayRef);
    }

    private static String failed(String eventId, String gatewayRef) {
        return """
                {"id":"%s","type":"payment_intent.payment_failed","data":{"object":{\
                "id":"%s","last_payment_error":{"code":"card_declined"}}}}"""
                .formatted(eventId, gatewayRef);
    }

    private String refOf(PaymentType type) {
        return paymentRepository.findByBookingIdOrderByIdAsc(bookingId).stream()
                .filter(p -> p.getType() == type)
                .findFirst()
                .map(Payment::getGatewayRef)
                .orElseThrow(() -> new AssertionError("no " + type + " payment"));
    }
}
