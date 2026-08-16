package com.rentflow.settlement;

import com.rentflow.booking.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReturnRepository extends JpaRepository<Return, Long> {

    /** The replay lookup: a second "confirm return" gets the first one's record back. */
    Optional<Return> findByBookingId(Long bookingId);

    /**
     * Returns whose dispute window has passed and whose booking is still awaiting release.
     *
     * A subquery on status rather than a join, matching {@code findByItemOwnerId}: these
     * entities reference each other by id, not by association.
     */
    @Query("""
            SELECT r FROM Return r
            WHERE r.createdAt < :cutoff
              AND r.bookingId IN (SELECT b.id FROM Booking b WHERE b.status = :status)
            ORDER BY r.id
            """)
    List<Return> findReleasable(@Param("cutoff") Instant cutoff, @Param("status") BookingStatus status);
}
