package com.rentflow.booking;

import com.rentflow.booking.dto.CreateBookingRequest;
import com.rentflow.common.exception.BookingConflictException;
import com.rentflow.common.exception.ForbiddenException;
import com.rentflow.common.exception.InvalidDateRangeException;
import com.rentflow.item.Item;
import com.rentflow.item.ItemService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Booking lifecycle. Today this is the single-threaded happy path (Day 7);
 * the Redis distributed lock and SELECT ... FOR UPDATE that make it safe under real
 * concurrency arrive in Day 8. The DB exclusion constraint from V3 already guards the
 * gap in the meantime — see {@link #create}.
 */
@Service
public class BookingService {

    /** A guard against absurd requests (and against one booking blocking an item forever). */
    private static final long MAX_RENTAL_DAYS = 90;

    private final BookingRepository bookingRepository;
    private final ItemService itemService;

    public BookingService(BookingRepository bookingRepository, ItemService itemService) {
        this.bookingRepository = bookingRepository;
        this.itemService = itemService;
    }

    /**
     * Bookings that block [from, to] for this item. Empty list means the dates are free.
     * Backs GET /items/{id}/availability.
     */
    @Transactional(readOnly = true)
    public List<Booking> findBlocking(Long itemId, LocalDate from, LocalDate to) {
        requireValidRange(from, to);
        itemService.get(itemId);   // 404 if the item doesn't exist
        return bookingRepository.findOverlapping(itemId, BookingStatus.BLOCKING, from, to);
    }

    /**
     * Create a booking in PENDING_PAYMENT, holding the dates until payment resolves.
     *
     * Two defences, deliberately layered:
     *  1. the overlap query below — fast, gives a friendly error, but is a check-then-act
     *     race until Day 8 adds locking;
     *  2. the `no_overlapping_active_bookings` exclusion constraint — the database refuses
     *     to store the row at all. saveAndFlush() forces that check to fire here, inside
     *     this method, rather than at commit time where we could no longer translate it.
     */
    @Transactional
    public Booking create(CreateBookingRequest request, Long renterId) {
        LocalDate start = request.startDate();
        LocalDate end = request.endDate();
        requireValidRange(start, end);

        Item item = itemService.get(request.itemId());   // 404 if missing

        if (!"ACTIVE".equals(item.getStatus())) {
            throw new BookingConflictException("Item " + item.getId() + " is not available for booking");
        }
        // Renting from yourself is meaningless and would let an owner block their own calendar.
        if (item.getOwnerId().equals(renterId)) {
            throw new ForbiddenException("You cannot book your own item");
        }

        if (!bookingRepository.findOverlapping(item.getId(), BookingStatus.BLOCKING, start, end).isEmpty()) {
            throw new BookingConflictException(
                    "Item " + item.getId() + " is already booked between " + start + " and " + end);
        }

        Booking booking = new Booking(
                item.getId(),
                renterId,
                start,
                end,
                totalFor(item, start, end),
                item.getDepositAmount()      // snapshot — a later item edit must not change this
        );

        try {
            return bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            // The exclusion constraint caught what the check above missed — a genuine race.
            throw new BookingConflictException(
                    "Item " + item.getId() + " was just booked for those dates");
        }
    }

    /** A renter's own bookings. Backs GET /bookings/me. */
    @Transactional(readOnly = true)
    public List<Booking> findMine(Long renterId) {
        return bookingRepository.findByRenterIdOrderByStartDateDesc(renterId);
    }

    /**
     * Rent = daily rate x billable days, where both ends count: 1st→1st is one day.
     * Kept in one place so a pricing Strategy (weekly rates, promotions) can replace it later.
     */
    private BigDecimal totalFor(Item item, LocalDate start, LocalDate end) {
        long days = billableDays(start, end);
        return item.getDailyRate().multiply(BigDecimal.valueOf(days));
    }

    private static long billableDays(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    private static void requireValidRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new InvalidDateRangeException("Both start and end dates are required");
        }
        if (end.isBefore(start)) {
            throw new InvalidDateRangeException("End date must not be before start date");
        }
        if (billableDays(start, end) > MAX_RENTAL_DAYS) {
            throw new InvalidDateRangeException("A booking cannot exceed " + MAX_RENTAL_DAYS + " days");
        }
    }
}
