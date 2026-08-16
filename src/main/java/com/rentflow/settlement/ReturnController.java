package com.rentflow.settlement;

import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStatus;
import com.rentflow.common.exception.NotFoundException;
import com.rentflow.security.AuthenticatedUser;
import com.rentflow.settlement.dto.RecordReturnRequest;
import com.rentflow.settlement.dto.ReturnResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner confirming their item is back. POST, not PUT: it records an event that happened,
 * and it is the trigger for the deposit split.
 */
@RestController
public class ReturnController {

    private final SettlementService settlementService;
    private final BookingRepository bookingRepository;

    public ReturnController(SettlementService settlementService, BookingRepository bookingRepository) {
        this.settlementService = settlementService;
        this.bookingRepository = bookingRepository;
    }

    /** Returns 200 on a repeat: the same confirmation must give the same answer. */
    @PostMapping("/bookings/{id}/return")
    public ReturnResponse record(@PathVariable Long id,
                                 @Valid @RequestBody RecordReturnRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        Return record = settlementService.recordReturn(id, user.id(), request);
        return ReturnResponse.from(record, statusOf(id));
    }

    private BookingStatus statusOf(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId))
                .getStatus();
    }
}
