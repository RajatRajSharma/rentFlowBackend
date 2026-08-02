package com.rentflow.security;

import com.rentflow.user.Role;

/**
 * The "who am I" object attached to each authenticated request. Built from the JWT
 * claims by JwtAuthFilter and stored as the security principal, so controllers can
 * read it with @AuthenticationPrincipal AuthenticatedUser. Its `id` drives ownership checks.
 */
public record AuthenticatedUser(Long id, String email, Role role) {
}
