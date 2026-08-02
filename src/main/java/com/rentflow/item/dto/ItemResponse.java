package com.rentflow.item.dto;

import com.rentflow.item.Item;

import java.math.BigDecimal;

/** What we return for an item. */
public record ItemResponse(
        Long id,
        Long ownerId,
        String title,
        String description,
        BigDecimal dailyRate,
        BigDecimal depositAmount,
        String status
) {
    public static ItemResponse from(Item item) {
        return new ItemResponse(
                item.getId(),
                item.getOwnerId(),
                item.getTitle(),
                item.getDescription(),
                item.getDailyRate(),
                item.getDepositAmount(),
                item.getStatus()
        );
    }
}
