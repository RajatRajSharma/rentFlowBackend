package com.rentflow.common.exception;

/**
 * Thrown when a distributed lock could not be acquired in time — the item is under heavy
 * contention right now. Handled centrally → HTTP 409 Conflict, because retrying may well work.
 */
public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(String key) {
        super("Could not acquire lock on " + key + " — too many concurrent requests, please retry");
    }
}
