package com.rentflow.event;

/**
 * Announce a fact without knowing who cares. Day 17 swaps RabbitMQ in behind this, and
 * nothing that publishes changes. Implementations deliver only AFTER the caller commits.
 */
public interface EventPublisher {

    void publish(DomainEvent event);
}
