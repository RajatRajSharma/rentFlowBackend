package com.rentflow.security;

import com.rentflow.common.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

/**
 * Makes security rejections look like every other error.
 *
 * The problem this solves: Spring Security rejects requests inside the FILTER CHAIN, which runs
 * before the DispatcherServlet — so GlobalExceptionHandler never sees them, and callers got
 * Spring's default HTML-ish body instead of our ApiError JSON.
 *
 * It also fixes the status code. Previously a missing token produced 403; the correct answer is
 * 401 ("who are you?"), with 403 reserved for "I know who you are, and you still may not".
 */
@Component
public class RestAuthEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAuthEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** No credentials, or credentials we couldn't verify → 401. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED,
                "Authentication required — send a valid 'Authorization: Bearer <token>' header");
    }

    /** Authenticated, but not allowed to do this → 403. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
    }

    private void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = new ApiError(
                Instant.now(), status.value(), status.getReasonPhrase(), message, null);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
