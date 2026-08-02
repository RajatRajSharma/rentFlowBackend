package com.rentflow.item.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Body for PUT /items/{id}. Only the owner may apply it (checked in the service). */
public record UpdateItemRequest(

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
