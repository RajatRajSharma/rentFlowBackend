package com.rentflow.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Log it, keep it, move on — for runs with no broker. Selected with
 * {@code app.events.publisher=memory}; the default is {@link RabbitEventPublisher}.
 */
@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "memory")
public class InMemoryPublisher extends AfterCommitPublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPublisher.class);

    /** A window on what just happened, for tests and local debugging — not a queue. */
    private static final int RETAINED = 200;

    private final Deque<DomainEvent> recent = new ArrayDeque<>();

    /** Events delivered so far, oldest first. Bounded, so a long run can't grow here. */
    public synchronized List<DomainEvent> recent() {
        return new ArrayList<>(recent);
    }

    public synchronized void clear() {
        recent.clear();
    }

    @Override
    protected synchronized void deliver(DomainEvent event) {
        log.info("event {} booking={} at={}", event.type(), event.bookingId(), event.occurredAt());
        recent.addLast(event);
        if (recent.size() > RETAINED) {
            recent.removeFirst();
        }
    }
}
