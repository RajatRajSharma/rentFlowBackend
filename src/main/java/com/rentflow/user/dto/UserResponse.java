package com.rentflow.user.dto;

import com.rentflow.user.Role;
import com.rentflow.user.User;

/**
 * What we send BACK to the client. Note there is no passwordHash field —
 * DTOs at the boundary let us expose exactly what's safe, never the entity directly.
 */
public record UserResponse(
        Long id,
        String name,
        String email,
        Role role
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
