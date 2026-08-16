package com.rentflow.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default publisher: onto the topic exchange, keyed by {@link DomainEvent#type()}.
 */
@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "rabbit", matchIfMissing = true)
public class RabbitEventPublisher extends AfterCommitPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    protected void deliver(DomainEvent event) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, event.type(), event);
            log.debug("published {} for booking {}", event.type(), event.bookingId());
        } catch (AmqpException ex) {
            // The transaction has already committed, so throwing here would fail a request
            // whose work is done and durable. Loud log instead — and Day 20's outbox is the
            // real fix, because a lost notification should be replayable, not just logged.
            log.error("LOST EVENT {} for booking {} — broker unreachable",
                    event.type(), event.bookingId(), ex);
        }
    }
}
