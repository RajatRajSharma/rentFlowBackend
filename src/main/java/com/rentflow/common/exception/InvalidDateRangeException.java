package com.rentflow.common.exception;

/**
 * Thrown when a date range is nonsensical — end before start, or a window that's too long.
 * Handled centrally → HTTP 400 Bad Request (the caller sent something wrong).
 */
public class InvalidDateRangeException extends RuntimeException {

    public InvalidDateRangeException(String message) {
        super(message);
    }
}
