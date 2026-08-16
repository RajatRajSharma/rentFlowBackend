package com.rentflow.settlement.dto;

import com.rentflow.booking.BookingStatus;
import com.rentflow.settlement.Return;
import com.rentflow.settlement.ReturnCondition;

import java.math.BigDecimal;

/** What the owner gets back after confirming a return, including where the booking landed. */
public record ReturnResponse(
        Long id,
        Long bookingId,
        ReturnCondition condition,
        BigDecimal depositDeducted,
        BigDecimal refundAmount,
        String notes,
        BookingStatus bookingStatus
) {

    public static ReturnResponse from(Return record, BookingStatus bookingStatus) {
        return new ReturnResponse(record.getId(), record.getBookingId(), record.getCondition(),
                record.getDepositDeducted(), record.getRefundAmount(), record.getNotes(), bookingStatus);
    }
}
