package com.rentflow.settlement;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStateMachine;
import com.rentflow.booking.BookingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Closes bookings whose dispute window has passed with no claim — the deposit is final and
 * the rental is over.
 *
 * A worker rather than doing it at return time, because "nobody objected for N hours" is a
 * fact about elapsed time, and only a clock can notice it. DISPUTED bookings are untouched:
 * a claim is a human's to resolve.
 */
@Component
public class DepositReleaseWorker {

    private static final Logger log = LoggerFactory.getLogger(DepositReleaseWorker.class);

    private final ReturnRepository returnRepository;
    private final BookingRepository bookingRepository;
    private final BookingStateMachine stateMachine;
    private final TransactionTemplate transactionTemplate;
    private final Duration disputeWindow;

    public DepositReleaseWorker(ReturnRepository returnRepository,
                                BookingRepository bookingRepository,
                                BookingStateMachine stateMachine,
                                TransactionTemplate transactionTemplate,
                                @Value("${app.workers.dispute-window-hours:24}") long disputeWindowHours) {
        this.returnRepository = returnRepository;
        this.bookingRepository = bookingRepository;
        this.stateMachine = stateMachine;
        this.transactionTemplate = transactionTemplate;
        this.disputeWindow = Duration.ofHours(disputeWindowHours);
    }

    @Scheduled(fixedDelayString = "${app.workers.deposit-release-interval-ms:300000}",
               initialDelayString = "${app.workers.initial-delay-ms:30000}")
    public void scheduled() {
        int released = releaseDue();
        if (released > 0) {
            log.info("Deposit release: closed {} booking(s)", released);
        }
    }

    /** @return how many bookings were closed. */
    public int releaseDue() {
        List<Return> due = returnRepository.findReleasable(
                Instant.now().minus(disputeWindow), BookingStatus.RETURNED);

        int released = 0;
        for (Return record : due) {
            try {
                released += close(record) ? 1 : 0;
            } catch (RuntimeException ex) {
                // One stuck booking must not stop everyone else's deposit coming back.
                log.error("Releasing deposit for booking {} failed", record.getBookingId(), ex);
            }
        }
        return released;
    }

    private boolean close(Return record) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            Booking booking = bookingRepository.findByIdForUpdate(record.getBookingId()).orElse(null);
            // Re-checked under the lock: a dispute may have been raised since the query.
            if (booking == null || !stateMachine.canTransition(booking.getStatus(), BookingStatus.CLOSED)) {
                return false;
            }
            booking.setStatus(BookingStatus.CLOSED);
            bookingRepository.save(booking);
            log.info("Booking {} CLOSED — ₹{} released to the renter",
                    booking.getId(), record.getRefundAmount());
            return true;
        }));
    }
}
