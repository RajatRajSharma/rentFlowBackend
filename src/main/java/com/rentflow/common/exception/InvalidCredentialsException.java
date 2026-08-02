package com.rentflow.common.exception;

/**
 * Thrown on a bad email/password at login. Deliberately vague message so we don't
 * reveal whether the email exists. Handled centrally → HTTP 401 Unauthorized.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
