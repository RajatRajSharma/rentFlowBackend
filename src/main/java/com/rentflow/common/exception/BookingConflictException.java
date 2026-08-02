package com.rentflow.common.exception;

/**
 * Thrown when the requested dates are already taken, or the item can't be booked right now.
 * Raised by the application-level overlap check and, as a backstop, when the database's
 * exclusion constraint rejects the insert. Handled centrally → HTTP 409 Conflict.
 */
public class BookingConflictException extends RuntimeException {

    public BookingConflictException(String message) {
        super(message);
    }
}
