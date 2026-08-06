package com.rentflow.booking;

import com.rentflow.booking.dto.CreateBookingRequest;
import com.rentflow.common.exception.BookingConflictException;
import com.rentflow.common.exception.LockAcquisitionException;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.support.IntegrationTestBase;
import com.rentflow.user.Role;
import com.rentflow.user.User;
import com.rentflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE test this whole project exists to make possible.
 *
 * Fire hundreds of simultaneous requests for the same item and the same dates, and prove that
 * exactly one wins — not "usually one", not "one in the happy case". Everything else must be
 * cleanly rejected, and the database must end up with exactly one booking.
 */
@DisplayName("Concurrent booking of one slot")
class BookingConcurrencyIT extends IntegrationTestBase {

    private static final int CONCURRENT_REQUESTS = 500;
    private static final int POOL_SIZE = 64;

    private static final LocalDate START = LocalDate.now().plusDays(30);
    private static final LocalDate END = START.plusDays(4);   // 5 billable days

    @Autowired private BookingService bookingService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long itemId;
    private Long renterId;

    @BeforeEach
    void seed() {
        bookingRepository.deleteAll();
        itemRepository.deleteAll();
        userRepository.deleteAll();

        User owner = userRepository.save(new User("Owner", "owner@test.com", "x", Role.USER));
        User renter = userRepository.save(new User("Renter", "renter@test.com", "x", Role.USER));
        Item item = itemRepository.save(new Item(
                owner.getId(), "Contested Camera", "one of a kind",
                new BigDecimal("1000.00"), new BigDecimal("5000.00")));

        itemId = item.getId();
        renterId = renter.getId();
    }

    @Test
    @DisplayName("500 simultaneous requests -> exactly 1 booking, 0 double-bookings")
    void exactlyOneRequestWins() throws Exception {
        Result result = fireConcurrently(
                () -> bookingService.create(new CreateBookingRequest(itemId, START, END), renterId));

        List<Booking> stored = bookingRepository.findOverlapping(
                itemId, BookingStatus.BLOCKING, START, END);

        System.out.printf("""

                ==================== CONCURRENCY PROOF ====================
                 requests fired      : %d
                 succeeded           : %d
                 rejected (conflict) : %d
                 rejected (lock busy): %d
                 unexpected errors   : %d
                 bookings in database: %d
                ===========================================================
                %n""",
                CONCURRENT_REQUESTS, result.success.get(), result.conflict.get(),
                result.lockBusy.get(), result.unexpected.get(), stored.size());

        assertThat(result.success).hasValue(1);
        assertThat(stored).hasSize(1);
        assertThat(result.unexpected).as("no unexpected exception types").hasValue(0);
        assertThat(result.conflict.get() + result.lockBusy.get())
                .isEqualTo(CONCURRENT_REQUESTS - 1);
    }

    @Test
    @DisplayName("without the Redis lock, the database still refuses to double-book")
    void databaseDefencesHoldWithoutTheDistributedLock() throws Exception {
        // Bypass LockManager entirely and hammer the transactional path directly. This is what
        // would happen if Redis were down, or an instance had a bug and skipped the lock.
        // SELECT ... FOR UPDATE and the exclusion constraint are on their own here.
        Result result = fireConcurrently(() -> transactionTemplate.execute(status ->
                bookingService.createInTransaction(new CreateBookingRequest(itemId, START, END), renterId)));

        List<Booking> stored = bookingRepository.findOverlapping(
                itemId, BookingStatus.BLOCKING, START, END);

        System.out.printf("""

                ============ PROOF WITHOUT THE DISTRIBUTED LOCK ============
                 succeeded           : %d
                 rejected            : %d
                 bookings in database: %d
                ============================================================
                %n""", result.success.get(), result.conflict.get(), stored.size());

        assertThat(result.success).hasValue(1);
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("adjacent dates are not a conflict — both succeed under load")
    void nonOverlappingRequestsAllSucceed() throws Exception {
        AtomicInteger created = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        CountDownLatch start = new CountDownLatch(1);

        // 20 requests, each for its own consecutive 1-day slot. None overlap, so all must win.
        int slots = 20;
        for (int i = 0; i < slots; i++) {
            LocalDate day = START.plusDays(100 + i);
            pool.submit(() -> {
                start.await();
                bookingService.create(new CreateBookingRequest(itemId, day, day), renterId);
                created.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();

        assertThat(created).as("locking must not reject legitimate, non-overlapping bookings")
                .hasValue(slots);
    }

    /** Releases every thread at once via a latch, so they genuinely collide. */
    private Result fireConcurrently(Callable<?> action) throws InterruptedException {
        Result result = new Result();
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        CountDownLatch startGun = new CountDownLatch(1);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            pool.submit(() -> {
                startGun.await();
                try {
                    action.call();
                    result.success.incrementAndGet();
                } catch (BookingConflictException ex) {
                    result.conflict.incrementAndGet();
                } catch (LockAcquisitionException ex) {
                    result.lockBusy.incrementAndGet();
                } catch (Exception ex) {
                    result.unexpected.incrementAndGet();
                    System.out.println("unexpected: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
                }
                return null;
            });
        }

        startGun.countDown();   // all threads go at once
        pool.shutdown();
        assertThat(pool.awaitTermination(3, TimeUnit.MINUTES))
                .as("all requests finished").isTrue();
        return result;
    }

    private static final class Result {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger conflict = new AtomicInteger();
        final AtomicInteger lockBusy = new AtomicInteger();
        final AtomicInteger unexpected = new AtomicInteger();
    }
}
