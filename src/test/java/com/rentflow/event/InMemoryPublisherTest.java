package com.rentflow.event;

import com.rentflow.payment.PaymentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that makes events safe: nothing is announced until the transaction that caused
 * it has committed.
 */
@DisplayName("InMemoryPublisher")
class InMemoryPublisherTest {

    private final InMemoryPublisher publisher = new InMemoryPublisher();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("with no transaction in play, delivery is immediate")
    void deliversImmediatelyOutsideATransaction() {
        publisher.publish(paymentSucceeded());

        assertThat(publisher.recent()).hasSize(1);
    }

    @Test
    @DisplayName("inside a transaction, nothing is delivered until commit")
    void waitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(paymentSucceeded());
        assertThat(publisher.recent())
                .as("the transaction can still roll back — the fact isn't true yet")
                .isEmpty();

        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        assertThat(registered).hasSize(1);
        registered.forEach(TransactionSynchronization::afterCommit);

        assertThat(publisher.recent()).hasSize(1);
    }

    @Test
    @DisplayName("a rollback publishes nothing at all")
    void rollbackPublishesNothing() {
        TransactionSynchronizationManager.initSynchronization();
        publisher.publish(paymentSucceeded());

        // The transaction dies without ever reaching afterCommit.
        TransactionSynchronizationManager.clearSynchronization();

        assertThat(publisher.recent())
                .as("an email about a payment the database never kept is worse than no email")
                .isEmpty();
    }

    @Test
    @DisplayName("the retained window is bounded")
    void retainsOnlyTheRecentPast() {
        for (int i = 0; i < 250; i++) {
            publisher.publish(BookingConfirmed.of((long) i, 1L, 2L));
        }

        assertThat(publisher.recent()).hasSize(200);
        assertThat(publisher.recent().get(0).bookingId()).isEqualTo(50L);
    }

    private static PaymentSucceeded paymentSucceeded() {
        return PaymentSucceeded.of(9L, 1L, PaymentType.FEE, new BigDecimal("2000.00"));
    }
}
