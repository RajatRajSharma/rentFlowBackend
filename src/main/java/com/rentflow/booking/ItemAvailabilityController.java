package com.rentflow.booking;

import com.rentflow.booking.dto.AvailabilityResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * GET /items/{id}/availability — public, so a visitor can see free dates before signing up.
 *
 * It lives in the booking package, not the item package, on purpose: availability is a
 * question about bookings. Putting it in ItemController would make item depend on booking,
 * while booking already depends on item — a cycle. A controller's URL doesn't have to match
 * its package.
 */
@RestController
public class ItemAvailabilityController {

    private final BookingService bookingService;

    public ItemAvailabilityController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping("/items/{id}/availability")
    public AvailabilityResponse availability(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return AvailabilityResponse.of(id, from, to, bookingService.findBlocking(id, from, to));
    }
}
