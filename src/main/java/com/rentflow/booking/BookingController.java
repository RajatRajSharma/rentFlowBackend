package com.rentflow.booking;

import com.rentflow.booking.dto.BookingResponse;
import com.rentflow.booking.dto.CreateBookingRequest;
import com.rentflow.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Booking endpoints. Both require a valid JWT — there is no anonymous booking.
 * The renter always comes from the token, so you can only ever book, or list, as yourself.
 */
@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@Valid @RequestBody CreateBookingRequest request,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return BookingResponse.from(bookingService.create(request, user.id()));
    }

    @GetMapping("/me")
    public List<BookingResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return bookingService.findMine(user.id()).stream().map(BookingResponse::from).toList();
    }

    /**
     * Cancel a booking you made. POST rather than DELETE: cancelling is a state change that
     * keeps the record (we still need it for history, refunds and analytics), not a deletion.
     */
    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable Long id,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return BookingResponse.from(bookingService.cancel(id, user.id()));
    }
}
