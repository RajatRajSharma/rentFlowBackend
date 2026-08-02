package com.rentflow.common.exception;

/**
 * Thrown when something tries to move a booking to a status it cannot legally reach —
 * e.g. cancelling a booking that has already been returned.
 * Handled centrally → HTTP 409 Conflict (the request is valid, the current state forbids it).
 */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(Object from, Object to) {
        super("Cannot move a booking from " + from + " to " + to);
    }
}
