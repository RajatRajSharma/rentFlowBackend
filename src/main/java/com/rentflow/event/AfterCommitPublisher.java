package com.rentflow.event;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one rule every publisher obeys: nothing goes out until the transaction that caused it
 * has committed. Subclasses only implement {@link #deliver}.
 */
public abstract class AfterCommitPublisher implements EventPublisher {

    @Override
    public final void publish(DomainEvent event) {
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

    /** Called once the fact is true and durable. Must not throw the caller's request away. */
    protected abstract void deliver(DomainEvent event);
}
