package com.rentflow.booking.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Body for POST /bookings. The renter is taken from the JWT, never from the request,
 * and the price is computed server-side from the item — a client cannot name its own price.
 *
 * Dates are inclusive: startDate 2026-08-10, endDate 2026-08-12 is three billable days.
 */
public record CreateBookingRequest(

        @NotNull(message = "itemId is required")
        Long itemId,

        @NotNull(message = "startDate is required")
        @FutureOrPresent(message = "startDate cannot be in the past")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate startDate,

        @NotNull(message = "endDate is required")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate endDate
) {
}
