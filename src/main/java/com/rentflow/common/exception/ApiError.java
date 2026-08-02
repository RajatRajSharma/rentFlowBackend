package com.rentflow.common.exception;

import java.time.Instant;
import java.util.Map;

/**
 * A uniform error body returned for every handled exception, so clients always
 * get the same JSON shape. fieldErrors is only populated for validation failures.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors
) {
}
