package com.rentflow.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The starting implementation: log it, keep it, move on. Day 17 swaps in RabbitMQ behind
 * the same interface.
 */
@Component
public class InMemoryPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPublisher.class);

    /** A window on what just happened, for tests and local debugging — not a queue. */
    private static final int RETAINED = 200;

    private final Deque<DomainEvent> recent = new ArrayDeque<>();

    /**
     * Held until the caller's transaction commits: an email saying "your booking is
     * confirmed" for a booking the database never kept is worse than a late one.
     */
    @Override
    public void publish(DomainEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deliver(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliver(event);
            }
        });
    }

    /** Events delivered so far, oldest first. Bounded, so a long-running app can't grow here. */
    public synchronized List<DomainEvent> recent() {
        return new ArrayList<>(recent);
    }

    public synchronized void clear() {
        recent.clear();
    }

    private synchronized void deliver(DomainEvent event) {
        log.info("event {} booking={} at={}", event.type(), event.bookingId(), event.occurredAt());
        recent.addLast(event);
        if (recent.size() > RETAINED) {
            recent.removeFirst();
        }
    }
}
