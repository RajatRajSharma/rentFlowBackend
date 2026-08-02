package com.rentflow.common.exception;

/**
 * Thrown when an authenticated user tries to act on a resource they don't own.
 * Handled centrally → HTTP 403 Forbidden.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
