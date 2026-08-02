package com.rentflow.common.exception;

/**
 * Thrown when someone registers with an email that already exists.
 * Handled centrally by GlobalExceptionHandler → HTTP 409 Conflict.
 */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("Email already registered: " + email);
    }
}
