package com.rentflow.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The shape of a POST /auth/register body. A `record` is an immutable data carrier.
 * The annotations are validated by @Valid in the controller before any logic runs;
 * failures are turned into a clean 400 by GlobalExceptionHandler.
 */
public record RegisterRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "email is required")
        @Email(message = "must be a valid email")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 8, message = "password must be at least 8 characters")
        String password
) {
}
