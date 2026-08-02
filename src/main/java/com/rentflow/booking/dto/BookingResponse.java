package com.rentflow.booking.dto;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** What we return for a booking. */
public record BookingResponse(
        Long id,
        Long itemId,
        Long renterId,
        LocalDate startDate,
        LocalDate endDate,
        long days,
        BookingStatus status,
        BigDecimal totalAmount,
        BigDecimal depositAmount
) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getItemId(),
                booking.getRenterId(),
                booking.getStartDate(),
                booking.getEndDate(),
                ChronoUnit.DAYS.between(booking.getStartDate(), booking.getEndDate()) + 1,
                booking.getStatus(),
                booking.getTotalAmount(),
                booking.getDepositAmount()
        );
    }
}
