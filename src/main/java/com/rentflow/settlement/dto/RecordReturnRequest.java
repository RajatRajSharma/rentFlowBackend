package com.rentflow.settlement.dto;

import com.rentflow.settlement.ReturnCondition;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Body for POST /bookings/{id}/return. The refund isn't here on purpose — it is the deposit
 * minus the deduction, computed server-side, so an owner can't name what they hand back.
 */
public record RecordReturnRequest(

        @NotNull(message = "condition is required")
        ReturnCondition condition,

        /** What the owner claims for damage. Null means nothing claimed. */
        @DecimalMin(value = "0.00", message = "depositDeducted cannot be negative")
        BigDecimal depositDeducted,

        String notes
) {

    public BigDecimal deductionOrZero() {
        return depositDeducted == null ? BigDecimal.ZERO : depositDeducted;
    }
}
