package com.rentflow.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Body for POST /auth/login. */
public record LoginRequest(

        @NotBlank(message = "email is required")
        @Email(message = "must be a valid email")
        String email,

        @NotBlank(message = "password is required")
        String password
) {
}
