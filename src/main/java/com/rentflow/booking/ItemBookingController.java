package com.rentflow.booking;

import com.rentflow.booking.dto.AvailabilityResponse;
import com.rentflow.booking.dto.BookingResponse;
import com.rentflow.security.AuthenticatedUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Booking queries whose URL is item-scoped.
 *
 * These live in the booking package, not the item package, on purpose: both are questions
 * ABOUT bookings. Putting them in ItemController would make item depend on booking, while
 * booking already depends on item — a cycle. A controller's URL need not match its package.
 */
@RestController
public class ItemBookingController {

    private final BookingService bookingService;

    public ItemBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** Public — a visitor should see free dates before signing up. */
    @GetMapping("/items/{id}/availability")
    public AvailabilityResponse availability(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return AvailabilityResponse.of(id, from, to, bookingService.findBlocking(id, from, to));
    }

    /**
     * The owner's side of the marketplace: every booking placed on items I own.
     * Authenticated; the owner id comes from the token, so you can only ever see your own.
     */
    @GetMapping("/items/mine/bookings")
    public List<BookingResponse> myItemsBookings(@AuthenticationPrincipal AuthenticatedUser user) {
        return bookingService.findForOwner(user.id()).stream().map(BookingResponse::from).toList();
    }
}
