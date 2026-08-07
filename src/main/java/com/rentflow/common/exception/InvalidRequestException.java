package com.rentflow.common.exception;

/**
 * The request is well-formed JSON but semantically wrong in a way bean validation can't
 * express — a blank idempotency key, for instance. Handled centrally → HTTP 400.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
