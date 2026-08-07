package com.rentflow.payment;

import com.rentflow.booking.Booking;
import com.rentflow.booking.BookingRepository;
import com.rentflow.booking.BookingStateMachine;
import com.rentflow.booking.BookingStatus;
import com.rentflow.common.exception.BookingConflictException;
import com.rentflow.common.exception.IllegalTransitionException;
import com.rentflow.common.exception.InvalidRequestException;
import com.rentflow.common.exception.NotFoundException;
import com.rentflow.payment.dto.PaymentIntentResponse;
import com.rentflow.payment.dto.PaymentResponse;
import com.rentflow.payment.gateway.GatewayIntent;
import com.rentflow.payment.gateway.GatewayIntentRequest;
import com.rentflow.payment.gateway.PaymentGateway;
import com.rentflow.security.OwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Taking money for a booking, exactly once.
 *
 * <h3>The ordering is the design</h3>
 * <pre>
 *   1. reserve  (transaction) — write PENDING payment rows, claiming the idempotency key
 *   2. charge   (no transaction) — call the gateway
 *   3. record   (transaction) — store the gateway's reference
 * </pre>
 *
 * Reserving <em>before</em> calling out is what makes a retry safe: the key is claimed in
 * our database before any money can move, so a duplicate request that arrives while the
 * first gateway call is still in flight collides on the UNIQUE constraint instead of
 * starting a second charge. Doing it the other way round — charge, then write the row —
 * means a crash in between takes the customer's money with no record of it.
 *
 * The gateway call sits deliberately outside a transaction. A network call inside one
 * pins a database connection (and any locks it holds) for as long as someone else's
 * outage lasts, which turns a slow gateway into a dead application.
 *
 * <h3>What this class does not do</h3>
 * It never decides that a payment succeeded. Creating an intent means the gateway agreed
 * to try. Only the webhook (Day 13) or the reconciliation sweep (Day 18) moves a payment
 * to SUCCEEDED and a booking to CONFIRMED.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Comfortably above a UUID, well below anything that would bloat the unique index. */
    private static final int MAX_KEY_LENGTH = 200;

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final BookingStateMachine stateMachine;
    private final IdempotencyService idempotencyService;
    private final OwnershipGuard ownershipGuard;
    private final PaymentGateway gateway;
    private final TransactionTemplate transactionTemplate;
    private final String currency;

    public PaymentService(PaymentRepository paymentRepository,
                          BookingRepository bookingRepository,
                          BookingStateMachine stateMachine,
                          IdempotencyService idempotencyService,
                          OwnershipGuard ownershipGuard,
                          PaymentGateway gateway,
                          TransactionTemplate transactionTemplate,
                          @Value("${app.payment.currency:INR}") String currency) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.stateMachine = stateMachine;
        this.idempotencyService = idempotencyService;
        this.ownershipGuard = ownershipGuard;
        this.gateway = gateway;
        this.transactionTemplate = transactionTemplate;
        this.currency = currency;
    }

    /**
     * Create (or replay) the payment intents for a booking. Backs POST /bookings/{id}/pay.
     *
     * @param clientKey the caller's Idempotency-Key header. Two requests carrying the same
     *                  key for the same booking are the same request, however far apart.
     */
    public PaymentIntentResponse pay(Long bookingId, Long renterId, String clientKey) {
        requireUsableKey(clientKey);

        // Always validated, on both the first call and every replay — a 403 must stay a
        // 403 no matter how many times it is retried.
        Booking booking = loadForRenter(bookingId, renterId);

        List<String> keys = derivedKeys(bookingId, clientKey);

        // The lock scope is the BOOKING, not the booking-plus-key. Two attempts carrying
        // different keys are still two attempts to pay for the same thing, and they must
        // not be able to run reserve() concurrently and each create their own charges.
        // The replay lookup stays key-based — that is what makes a retry return its own
        // original result rather than someone else's.
        List<Payment> payments = idempotencyService.once(
                "pay:" + bookingId,
                () -> replay(keys),
                () -> reserve(booking, clientKey));

        // Step 2 + 3, outside the reservation transaction.
        List<PaymentResponse> lines = payments.stream().map(p -> charge(booking, p)).toList();

        return new PaymentIntentResponse(
                booking.getId(),
                booking.getStatus(),
                currency,
                lines.stream().map(PaymentResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add),
                lines);
    }

    /** Every payment recorded against a booking. Renter-only, same guard as paying. */
    public List<PaymentResponse> findForBooking(Long bookingId, Long renterId) {
        loadForRenter(bookingId, renterId);
        return paymentRepository.findByBookingIdOrderByIdAsc(bookingId).stream()
                .map(p -> PaymentResponse.from(p, null))
                .toList();
    }

    // ---------------------------------------------------------------- step 1: reserve

    /**
     * Write the PENDING rows. Runs in its own transaction so the keys are durably claimed
     * before we talk to anyone.
     *
     * The status check lives HERE rather than beside the ownership check, and that
     * placement matters: it must gate creating new payments, not replaying existing ones.
     * Once the webhook lands the booking is CONFIRMED, and a client retrying its original
     * pay request should still get its intents back — not a 409 telling it that the thing
     * it just successfully paid for cannot be paid for.
     */
    private List<Payment> reserve(Booking booking, String clientKey) {
        // "Could this booking still become CONFIRMED?" is exactly the question worth
        // asking, and the state machine already owns the answer — no second rulebook here.
        if (!stateMachine.canTransition(booking.getStatus(), BookingStatus.CONFIRMED)) {
            throw new IllegalTransitionException(booking.getStatus(), BookingStatus.CONFIRMED);
        }

        // A booking has at most ONE live set of charges, whatever key asks for them.
        // Without this, a renter who retried from a fresh browser tab — new key, same
        // booking — would get a second fee and a second deposit, and both intents could
        // then be paid. Key-level idempotency alone cannot catch that: the keys differ,
        // so nothing collides. FAILED rows are excluded on purpose, because a declined
        // card is exactly the case where a genuinely new attempt should be allowed.
        List<Payment> live = paymentRepository.findByBookingIdOrderByIdAsc(booking.getId()).stream()
                .filter(p -> p.getStatus() != PaymentStatus.FAILED)
                .toList();
        if (!live.isEmpty()) {
            log.debug("Booking {} already has {} live payment(s); reusing them", booking.getId(), live.size());
            return live;
        }

        List<Payment> toCreate = new ArrayList<>();
        addIfPositive(toCreate, booking, PaymentType.FEE, booking.getTotalAmount(), clientKey);
        addIfPositive(toCreate, booking, PaymentType.DEPOSIT, booking.getDepositAmount(), clientKey);

        if (toCreate.isEmpty()) {
            // A booking with nothing to charge can never be confirmed by a payment webhook,
            // so it would sit PENDING_PAYMENT forever. Better to say so than to hang.
            throw new BookingConflictException(
                    "Booking " + booking.getId() + " has no amount to charge");
        }

        return transactionTemplate.execute(status -> paymentRepository.saveAll(toCreate));
    }

    /** Skips zero amounts: a free rental or a no-deposit item should not create a ₹0 charge. */
    private void addIfPositive(List<Payment> target, Booking booking, PaymentType type,
                               BigDecimal amount, String clientKey) {
        if (amount != null && amount.signum() > 0) {
            target.add(new Payment(booking.getId(), type, amount, derivedKey(booking.getId(), clientKey, type)));
        }
    }

    // ----------------------------------------------------- steps 2 + 3: charge, record

    /**
     * Ask the gateway to prepare this charge, then record its reference.
     *
     * Called on replays too, on purpose. The gateway gets the same idempotency key, so it
     * replays its original intent rather than creating a second one — which both proves
     * the two-layer dedupe works and hands the client a usable client secret again. (We
     * never store the secret: it is a bearer credential for one intent, and our database
     * is not where those belong.)
     */
    private PaymentResponse charge(Booking booking, Payment payment) {
        if (payment.getStatus().isSettled()) {
            // Already resolved by the webhook. Nothing left to pay, and no secret to hand out.
            return PaymentResponse.from(payment, null);
        }

        GatewayIntent intent = gateway.createIntent(new GatewayIntentRequest(
                payment.getIdempotencyKey(),
                payment.getAmount(),
                currency,
                booking.getId(),
                payment.getType() + " for booking " + booking.getId()));

        if (payment.getGatewayRef() == null) {
            // A conditional UPDATE, not an entity save — see the note on the repository
            // method. Under concurrent retries an entity save would fail every thread but
            // the first with an optimistic-lock error, for a write they all agreed on.
            int updated = transactionTemplate.execute(status ->
                    paymentRepository.attachGatewayRef(payment.getId(), intent.gatewayRef()));
            payment.attachGatewayRef(intent.gatewayRef());   // reflect it in this response
            if (updated > 0) {
                log.debug("Payment {} attached to gateway ref {}", payment.getId(), intent.gatewayRef());
            }
        }

        return PaymentResponse.from(payment, intent.clientSecret());
    }

    // ------------------------------------------------------------------------ helpers

    private Optional<List<Payment>> replay(List<String> keys) {
        List<Payment> existing = paymentRepository.findByIdempotencyKeyIn(keys);
        return existing.isEmpty() ? Optional.empty() : Optional.of(existing);
    }

    /**
     * A blank key is worse than no key: every blank-keyed request for a booking would
     * derive the same stored key and dedupe against each other, so a renter's second,
     * genuinely new attempt would silently replay the first. Reject it rather than
     * quietly do the wrong thing. The length cap keeps a hostile client from stuffing
     * megabytes into a UNIQUE-indexed column.
     */
    private static void requireUsableKey(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            throw new InvalidRequestException("Idempotency-Key must not be blank");
        }
        if (clientKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidRequestException(
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
    }

    private Booking loadForRenter(Long bookingId, Long renterId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
        // Only the renter pays. The owner receives money; they never initiate a charge.
        ownershipGuard.requireOwner(booking.getRenterId(), renterId);
        return booking;
    }

    /**
     * One client key becomes one stored key per money movement.
     *
     * Scoped by booking so the same key used against a different booking is a different
     * operation rather than a false collision, and suffixed by type so a single request
     * can legitimately create both a fee and a deposit row while still colliding with its
     * own retry. See the column comment in V4__payments_ledger.sql.
     */
    private static String derivedKey(Long bookingId, String clientKey, PaymentType type) {
        return "b" + bookingId + ":" + clientKey + ":" + type;
    }

    private static List<String> derivedKeys(Long bookingId, String clientKey) {
        return List.of(
                derivedKey(bookingId, clientKey, PaymentType.FEE),
                derivedKey(bookingId, clientKey, PaymentType.DEPOSIT));
    }
}
