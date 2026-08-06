package com.rentflow.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Data access for bookings, including the overlap query that availability and booking
 * creation both depend on.
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Bookings on this item whose dates collide with [from, to].
     *
     * Two inclusive ranges overlap iff  existing.start <= new.end  AND  existing.end >= new.start.
     * That single condition covers every case — overlap at the front, at the back, fully inside,
     * or fully surrounding — which is why there is no need to enumerate them.
     *
     * Callers pass {@link BookingStatus#BLOCKING} so cancelled and failed bookings free their dates.
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.itemId = :itemId
              AND b.status IN :statuses
              AND b.startDate <= :to
              AND b.endDate   >= :from
            ORDER BY b.startDate
            """)
    List<Booking> findOverlapping(@Param("itemId") Long itemId,
                                  @Param("statuses") Collection<BookingStatus> statuses,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    /** A renter's own bookings, newest trip first. Backs GET /bookings/me. */
    List<Booking> findByRenterIdOrderByStartDateDesc(Long renterId);

    /**
     * Bookings placed on items owned by this user. Backs GET /items/mine/bookings.
     *
     * A subquery rather than a JPA association: Booking stores a plain {@code itemId}, not an
     * {@code @ManyToOne Item}. Keeping the entities decoupled means the booking feature can't
     * accidentally lazy-load half the item graph, and one query does the job.
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.itemId IN (SELECT i.id FROM Item i WHERE i.ownerId = :ownerId)
            ORDER BY b.startDate DESC
            """)
    List<Booking> findByItemOwnerId(@Param("ownerId") Long ownerId);
}
