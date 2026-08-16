package com.rentflow.settlement;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingActivationWorker;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStatus;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.settlement.dto.RecordReturnRequest;
import com.rentflow.support.IntegrationTestBase;
import com.rentflow.user.Role;
import com.rentflow.user.User;
import com.rentflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 18: the two things only a clock can notice — a rental starting, and a dispute window
 * closing. Invoked directly rather than waited for; scheduling is off in tests.
 */
@DisplayName("Scheduled workers")
class ScheduledWorkersIT extends IntegrationTestBase {

    private static final BigDecimal DAILY_RATE = new BigDecimal("1000.00");
    private static final BigDecimal DEPOSIT = new BigDecimal("5000.00");
    private static final BigDecimal FEE = new BigDecimal("2000.00");

    @Autowired private BookingActivationWorker activationWorker;
    @Autowired private DepositReleaseWorker depositReleaseWorker;
    @Autowired private SettlementService settlementService;
    @Autowired private ReturnRepository returnRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long ownerId;
    private Long renterId;
    private Long itemId;

    @BeforeEach
    void seed() {
        User owner = userRepository.save(new User("Owner", "owner@test.com", "x", Role.USER));
        User renter = userRepository.save(new User("Renter", "renter@test.com", "x", Role.USER));
        Item item = itemRepository.save(new Item(
                owner.getId(), "Sony A7 III", "2 batteries", DAILY_RATE, DEPOSIT));

        ownerId = owner.getId();
        renterId = renter.getId();
        itemId = item.getId();
    }

    @Test
    @DisplayName("activation starts today's rentals and leaves tomorrow's alone")
    void activationOnlyTouchesStartedRentals() {
        // Distinct dates: the exclusion constraint would reject two live bookings that overlap.
        Long started = booking(LocalDate.now().minusDays(1), BookingStatus.CONFIRMED);
        Long future = booking(LocalDate.now().plusDays(5), BookingStatus.CONFIRMED);
        Long cancelled = booking(LocalDate.now().minusDays(4), BookingStatus.CANCELLED);

        assertThat(activationWorker.activateDue()).isEqualTo(1);

        assertThat(statusOf(started)).isEqualTo(BookingStatus.ACTIVE);
        assertThat(statusOf(future)).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(statusOf(cancelled))
                .as("a cancelled booking must never be walked forward")
                .isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    @DisplayName("the sweep is safe to run twice")
    void activationIsIdempotent() {
        Long started = booking(LocalDate.now().minusDays(1), BookingStatus.CONFIRMED);

        assertThat(activationWorker.activateDue()).isEqualTo(1);
        assertThat(activationWorker.activateDue()).isZero();
        assertThat(statusOf(started)).isEqualTo(BookingStatus.ACTIVE);
    }

    @Test
    @DisplayName("a deposit is released once the dispute window has passed")
    void depositReleasedAfterTheWindow() {
        Long bookingId = returnedBooking(ReturnCondition.OK, null);

        // Still inside the window: the owner could yet raise a claim.
        assertThat(depositReleaseWorker.releaseDue()).isZero();
        assertThat(statusOf(bookingId)).isEqualTo(BookingStatus.RETURNED);

        ageReturnBy(bookingId, "2 days");

        assertThat(depositReleaseWorker.releaseDue()).isEqualTo(1);
        assertThat(statusOf(bookingId)).isEqualTo(BookingStatus.CLOSED);
    }

    @Test
    @DisplayName("a disputed return is never auto-closed, however long it sits")
    void disputesAreLeftForAHuman() {
        Long bookingId = returnedBooking(ReturnCondition.DAMAGED, new BigDecimal("1500.00"));
        assertThat(statusOf(bookingId)).isEqualTo(BookingStatus.DISPUTED);

        ageReturnBy(bookingId, "30 days");

        assertThat(depositReleaseWorker.releaseDue()).isZero();
        assertThat(statusOf(bookingId))
                .as("closing a dispute would pay out money that is still being argued over")
                .isEqualTo(BookingStatus.DISPUTED);
    }

    // ------------------------------------------------------------------------- helpers

    /** Inserts a booking directly in the given state — these tests are about the sweeps. */
    private Long booking(LocalDate start, BookingStatus status) {
        Booking booking = bookingRepository.save(new Booking(
                itemId, renterId, start, start.plusDays(1), FEE, DEPOSIT));
        booking.setStatus(status);
        return bookingRepository.save(booking).getId();
    }

    private Long returnedBooking(ReturnCondition condition, BigDecimal deducted) {
        Long bookingId = booking(LocalDate.now().minusDays(3), BookingStatus.ACTIVE);
        settlementService.recordReturn(bookingId, ownerId,
                new RecordReturnRequest(condition, deducted, null));
        return bookingId;
    }

    /** Backdates the return so the window has passed — cheaper than waiting a day. */
    private void ageReturnBy(Long bookingId, String interval) {
        jdbcTemplate.update(
                "UPDATE returns SET created_at = now() - CAST(? AS interval) WHERE booking_id = ?",
                interval, bookingId);
        returnRepository.flush();
    }

    private BookingStatus statusOf(Long bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow().getStatus();
    }
}
