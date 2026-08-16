package com.rentflow.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * Moves CONFIRMED bookings to ACTIVE once their start date arrives — the step that makes a
 * return possible, since only an ACTIVE booking can be handed back.
 *
 * Time passing is the only trigger, and no request will ever fire for it.
 */
@Component
public class BookingActivationWorker {

    private static final Logger log = LoggerFactory.getLogger(BookingActivationWorker.class);

    private final BookingRepository bookingRepository;
    private final BookingStateMachine stateMachine;
    private final TransactionTemplate transactionTemplate;

    public BookingActivationWorker(BookingRepository bookingRepository,
                                   BookingStateMachine stateMachine,
                                   TransactionTemplate transactionTemplate) {
        this.bookingRepository = bookingRepository;
        this.stateMachine = stateMachine;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${app.workers.activation-interval-ms:300000}",
               initialDelayString = "${app.workers.initial-delay-ms:30000}")
    public void scheduled() {
        int activated = activateDue();
        if (activated > 0) {
            log.info("Activation: {} booking(s) started today", activated);
        }
    }

    /** @return how many bookings became ACTIVE. */
    public int activateDue() {
        List<Booking> due = bookingRepository.findByStatusAndStartDateLessThanEqual(
                BookingStatus.CONFIRMED, LocalDate.now());

        int activated = 0;
        for (Booking booking : due) {
            try {
                activated += activate(booking.getId()) ? 1 : 0;
            } catch (RuntimeException ex) {
                log.error("Activating booking {} failed", booking.getId(), ex);
            }
        }
        return activated;
    }

    private boolean activate(Long bookingId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
            // Re-checked under the lock: it may have been cancelled since the query.
            if (booking == null || !stateMachine.canTransition(booking.getStatus(), BookingStatus.ACTIVE)) {
                return false;
            }
            booking.setStatus(BookingStatus.ACTIVE);
            bookingRepository.save(booking);
            log.debug("Booking {} is now ACTIVE", bookingId);
            return true;
        }));
    }
}
