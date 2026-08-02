package com.rentflow.user.dto;

/**
 * Returned on successful login. The client stores accessToken and sends it as
 * "Authorization: Bearer <accessToken>" on subsequent requests.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        UserResponse user
) {
    public static AuthResponse bearer(String token, UserResponse user) {
        return new AuthResponse(token, "Bearer", user);
    }
}
