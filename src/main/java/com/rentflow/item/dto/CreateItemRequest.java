package com.rentflow.item.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Body for POST /items. The owner is taken from the JWT, never from the request. */
public record CreateItemRequest(

        @NotBlank(message = "title is required")
        String title,

        String description,

        @NotNull(message = "dailyRate is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "dailyRate must be positive")
        BigDecimal dailyRate,

        @NotNull(message = "depositAmount is required")
        @DecimalMin(value = "0.0", message = "depositAmount cannot be negative")
        BigDecimal depositAmount
) {
}
