package com.rentflow.common.exception;

/** Thrown when a requested resource doesn't exist. Handled centrally → HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " not found: " + id);
    }
}
