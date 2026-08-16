package com.rentflow.notification;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.event.BookingConfirmed;
import com.rentflow.event.DomainEvent;
import com.rentflow.event.PaymentSucceeded;
import com.rentflow.event.RabbitConfig;
import com.rentflow.event.ReturnRecorded;
import com.rentflow.item.Item;
import com.rentflow.item.ItemRepository;
import com.rentflow.user.User;
import com.rentflow.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Turns events into emails, on a broker thread. Nothing here runs on the request path — a
 * slow mail server can no longer make a renter wait to find out their payment worked.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final EmailService emailService;
    private final BookingRepository bookingRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;

    public NotificationConsumer(EmailService emailService,
                                BookingRepository bookingRepository,
                                ItemRepository itemRepository,
                                UserRepository userRepository) {
        this.emailService = emailService;
        this.bookingRepository = bookingRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
    }

    @RabbitListener(queues = RabbitConfig.NOTIFICATIONS_QUEUE)
    public void on(DomainEvent event) {
        Parties parties = partiesFor(event.bookingId());
        if (parties == null) {
            // Dead-lettered rather than retried: the booking is gone, and no amount of
            // redelivery will bring it back.
            throw new ListenerExecutionFailedException(
                    "No booking " + event.bookingId() + " for event " + event.type(), null);
        }

        if (event instanceof PaymentSucceeded e) {
            emailService.send(parties.renterEmail(),
                    "We received your " + e.paymentType().name().toLowerCase() + " payment",
                    "₹%s for %s. Booking #%d.".formatted(e.amount(), parties.itemTitle(), e.bookingId()));

        } else if (event instanceof BookingConfirmed e) {
            // Both sides, deliberately: the renter needs the confirmation and the owner needs
            // to know their item is spoken for on those dates.
            emailService.send(parties.renterEmail(), "Your booking is confirmed",
                    "%s is yours from %s to %s. Booking #%d.".formatted(
                            parties.itemTitle(), parties.startDate(), parties.endDate(), e.bookingId()));
            emailService.send(parties.ownerEmail(), "Your item is booked",
                    "%s is booked from %s to %s. Booking #%d.".formatted(
                            parties.itemTitle(), parties.startDate(), parties.endDate(), e.bookingId()));

        } else if (event instanceof ReturnRecorded e) {
            emailService.send(parties.renterEmail(),
                    e.damaged() ? "Your return is under review" : "Your deposit is on its way",
                    "₹%s refunded, ₹%s claimed for damage. Booking #%d.".formatted(
                            e.refundAmount(), e.depositDeducted(), e.bookingId()));
            emailService.send(parties.ownerEmail(), "Return recorded",
                    "%s is back. Booking #%d.".formatted(parties.itemTitle(), e.bookingId()));
        }

        log.debug("notified on {} for booking {}", event.type(), event.bookingId());
    }

    private Parties partiesFor(Long bookingId) {
        Optional<Booking> booking = bookingRepository.findById(bookingId);
        if (booking.isEmpty()) {
            return null;
        }
        Item item = itemRepository.findById(booking.get().getItemId()).orElse(null);
        User renter = userRepository.findById(booking.get().getRenterId()).orElse(null);
        User owner = item == null ? null : userRepository.findById(item.getOwnerId()).orElse(null);
        if (item == null || renter == null || owner == null) {
            return null;
        }
        return new Parties(renter.getEmail(), owner.getEmail(), item.getTitle(),
                booking.get().getStartDate().toString(), booking.get().getEndDate().toString());
    }

    private record Parties(String renterEmail, String ownerEmail, String itemTitle,
                           String startDate, String endDate) {
    }
}
