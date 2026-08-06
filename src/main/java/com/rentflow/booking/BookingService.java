package com.rentflow.booking;

import com.rentflow.booking.dto.CreateBookingRequest;
import com.rentflow.common.exception.BookingConflictException;
import com.rentflow.common.exception.ForbiddenException;
import com.rentflow.common.exception.InvalidDateRangeException;
import com.rentflow.common.exception.NotFoundException;
import com.rentflow.item.Item;
import com.rentflow.item.ItemService;
import com.rentflow.security.OwnershipGuard;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Booking lifecycle, and the place the three anti-double-booking defences come together.
 *
 * <pre>
 *   1. Redis distributed lock on item:{id}   — one writer per item across ALL instances
 *   2. SELECT ... FOR UPDATE on the item row — the database serialises writers too
 *   3. EXCLUDE USING gist constraint         — overlapping rows are simply unstorable
 * </pre>
 *
 * Each layer alone has a hole: 1 fails if Redis is down, 2 fails if someone forgets the
 * transaction, 3 gives a correct but unfriendly error. Together they leave none.
 */
@Service
public class BookingService {

    /** A guard against absurd requests (and against one booking blocking an item forever). */
    private static final long MAX_RENTAL_DAYS = 90;

    private final BookingRepository bookingRepository;
    private final ItemService itemService;
    private final LockManager lockManager;
    private final OwnershipGuard ownershipGuard;
    private final BookingStateMachine stateMachine;
    private final TransactionTemplate transactionTemplate;

    public BookingService(BookingRepository bookingRepository,
                          ItemService itemService,
                          LockManager lockManager,
                          OwnershipGuard ownershipGuard,
                          BookingStateMachine stateMachine,
                          TransactionTemplate transactionTemplate) {
        this.bookingRepository = bookingRepository;
        this.itemService = itemService;
        this.lockManager = lockManager;
        this.ownershipGuard = ownershipGuard;
        this.stateMachine = stateMachine;
        this.transactionTemplate = transactionTemplate;
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
     * NOTE THE ORDERING — the lock is acquired OUTSIDE the transaction and released after it
     * commits. This method is deliberately NOT annotated {@code @Transactional}; it uses a
     * TransactionTemplate so the nesting is visible rather than hidden in an annotation.
     *
     * Getting this backwards (lock inside the transaction) is the classic bug: the lock would
     * be released while the transaction was still committing, letting a second writer read
     * stale data and book the same dates. The lock must outlive the commit.
     */
    public Booking create(CreateBookingRequest request, Long renterId) {
        // Cheap validation first — no point taking a distributed lock to reject a bad date range.
        requireValidRange(request.startDate(), request.endDate());

        return lockManager.withLock("item:" + request.itemId(),
                () -> transactionTemplate.execute(status -> createInTransaction(request, renterId)));
    }

    /**
     * The guarded work. Package-private so {@code BookingConcurrencyIT} can drive it without
     * the Redis lock and prove the database-level defences hold on their own.
     */
    Booking createInTransaction(CreateBookingRequest request, Long renterId) {
        LocalDate start = request.startDate();
        LocalDate end = request.endDate();

        // Defence 2: row lock. Concurrent bookings for this item now queue in the database.
        Item item = itemService.getForUpdate(request.itemId());

        if (!"ACTIVE".equals(item.getStatus())) {
            throw new BookingConflictException("Item " + item.getId() + " is not available for booking");
        }
        // Renting from yourself is meaningless and would let an owner block their own calendar.
        if (item.getOwnerId().equals(renterId)) {
            throw new ForbiddenException("You cannot book your own item");
        }

        // Defence 1's payload: the overlap check, now safe because we hold both locks.
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
            // Defence 3: saveAndFlush forces the exclusion constraint to fire HERE, inside this
            // method, where we can still translate it. A plain save() would defer the INSERT to
            // commit time, outside our reach, and surface as an opaque 500.
            return bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            throw new BookingConflictException(
                    "Item " + item.getId() + " was just booked for those dates");
        }
    }

    /** A renter's own bookings. Backs GET /bookings/me. */
    @Transactional(readOnly = true)
    public List<Booking> findMine(Long renterId) {
        return bookingRepository.findByRenterIdOrderByStartDateDesc(renterId);
    }

    /** Bookings placed on items I own. Backs GET /items/mine/bookings. */
    @Transactional(readOnly = true)
    public List<Booking> findForOwner(Long ownerId) {
        return bookingRepository.findByItemOwnerId(ownerId);
    }

    /**
     * Cancel a booking. Only the renter who made it may cancel, and only from a status the
     * state machine allows — PENDING_PAYMENT or CONFIRMED. Once the item has actually changed
     * hands (ACTIVE) there is nothing to cancel; it has to be returned.
     *
     * Cancelling frees the dates automatically: CANCELLED isn't in {@link BookingStatus#BLOCKING},
     * so it drops out of both the overlap query and the DB exclusion constraint.
     */
    @Transactional
    public Booking cancel(Long bookingId, Long currentUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));

        // Same guard as items: possession of the row decides, not a role.
        ownershipGuard.requireOwner(booking.getRenterId(), currentUserId);

        stateMachine.requireTransition(booking.getStatus(), BookingStatus.CANCELLED);
        booking.setStatus(BookingStatus.CANCELLED);
        return bookingRepository.save(booking);
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
