package com.rentflow.booking.dto;

import com.rentflow.booking.Booking;

import java.time.LocalDate;
import java.util.List;

/**
 * Answer to GET /items/{id}/availability. Public, so it deliberately leaks nothing about
 * WHO booked the dates — only which windows are taken, so a calendar can grey them out.
 */
public record AvailabilityResponse(
        Long itemId,
        LocalDate from,
        LocalDate to,
        boolean available,
        List<BookedRange> bookedRanges
) {
    /** One unavailable window inside the requested period. */
    public record BookedRange(LocalDate startDate, LocalDate endDate) {
    }

    public static AvailabilityResponse of(Long itemId, LocalDate from, LocalDate to, List<Booking> blocking) {
        List<BookedRange> ranges = blocking.stream()
                .map(b -> new BookedRange(b.getStartDate(), b.getEndDate()))
                .toList();
        return new AvailabilityResponse(itemId, from, to, ranges.isEmpty(), ranges);
    }
}
