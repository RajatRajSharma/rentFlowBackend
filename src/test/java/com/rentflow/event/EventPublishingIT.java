package com.rentflow.event;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 16: paying for a booking announces what happened, and a failure announces nothing.
 * Pinned to the in-memory publisher — what's under test is what gets published, not the broker.
 */
@DisplayName("Domain events")
@TestPropertySource(properties = "app.events.publisher=memory")
class EventPublishingIT extends IntegrationTestBase {

    private static final BigDecimal DAILY_RATE = new BigDecimal("1000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("5000.00");
    private static final BigDecimal FEE = new BigDecimal("2000.00");

    @Autowired private PaymentService paymentService;
    @Autowired private WebhookService webhookService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InMemoryPublisher publisher;
    @Autowired private FakeGateway fakeGateway;

    private Long bookingId;
    private Long itemId;
    private Long renterId;

    @BeforeEach
    void seed() {
        publisher.clear();

        User owner = userRepository.save(new User("Owner", "owner@test.com", "x", Role.USER));
        User renter = userRepository.save(new User("Renter", "renter@test.com", "x", Role.USER));
        Item item = itemRepository.save(new Item(
                owner.getId(), "Sony A7 III", "2 batteries", DAILY_RATE, DEPOSIT));

        LocalDate start = LocalDate.now().plusDays(10);
        Booking booking = bookingRepository.save(new Booking(
                item.getId(), renter.getId(), start, start.plusDays(1), FEE, DEPOSIT));

        bookingId = booking.getId();
        itemId = item.getId();
        renterId = renter.getId();

        paymentService.pay(bookingId, renterId, "key-1");
        publisher.clear();
    }

    @Test
    @DisplayName("each cleared charge is announced; the booking only once both are in")
    void successPublishesPaymentThenBooking() {
        deliver(succeeded("evt_fee", refOf(PaymentType.FEE)));

        assertThat(publisher.recent())
                .as("the fee cleared, but the booking has not")
                .singleElement()
                .isInstanceOfSatisfying(PaymentSucceeded.class, e -> {
                    assertThat(e.bookingId()).isEqualTo(bookingId);
                    assertThat(e.paymentType()).isEqualTo(PaymentType.FEE);
                    assertThat(e.amount()).isEqualByComparingTo(FEE);
                    assertThat(e.type()).isEqualTo("payment.succeeded");
                });

        deliver(succeeded("evt_dep", refOf(PaymentType.DEPOSIT)));

        assertThat(publisher.recent()).extracting(DomainEvent::type)
                .containsExactly("payment.succeeded", "payment.succeeded", "booking.confirmed");
        assertThat(publisher.recent()).last()
                .isInstanceOfSatisfying(BookingConfirmed.class, e -> {
                    assertThat(e.itemId()).isEqualTo(itemId);
                    assertThat(e.renterId()).isEqualTo(renterId);
                });
    }

    @Test
    @DisplayName("a failed payment announces nothing")
    void failurePublishesNothing() {
        deliver(failed("evt_fail", refOf(PaymentType.FEE)));

        // Events are facts about money that moved. Nothing moved here.
        assertThat(publisher.recent()).isEmpty();
    }

    @Test
    @DisplayName("a duplicate delivery announces nothing the second time")
    void duplicateDeliveryPublishesOnce() {
        String payload = succeeded("evt_fee", refOf(PaymentType.FEE));

        deliver(payload);
        deliver(payload);

        assertThat(publisher.recent()).hasSize(1);
    }

    // ------------------------------------------------------------------------- helpers

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
