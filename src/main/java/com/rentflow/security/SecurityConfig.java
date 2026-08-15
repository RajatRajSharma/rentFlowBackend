package com.rentflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Real security rules (replaces the earlier permit-all placeholder).
 *
 * - Stateless: no server session; identity comes from the JWT on each request.
 * - Public routes: register/login, actuator, version, and browsing items (GET).
 * - Everything else requires a valid token.
 * - Our JwtAuthFilter runs before Spring's username/password filter to read the token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RestAuthEntryPoint restAuthEntryPoint;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, RestAuthEntryPoint restAuthEntryPoint) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.restAuthEntryPoint = restAuthEntryPoint;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Token-based API: no CSRF, no server-side session.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/auth/**", "/actuator/**", "/api/version").permitAll()
                        // Gateway callbacks: there is no user and no token to present. The
                        // HMAC signature on the payload is the authentication, verified in
                        // WebhookService — so "permitAll" here means "authenticated by other
                        // means", not "open". An unsigned request still gets rejected.
                        .requestMatchers(HttpMethod.POST, "/webhooks/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/items", "/items/*", "/items/*/availability").permitAll()
                        // Everything else needs authentication
                        .anyRequest().authenticated())
                // Return our ApiError JSON for security rejections instead of Spring's default,
                // and answer 401 (not 403) when there are no usable credentials at all.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthEntryPoint)
                        .accessDeniedHandler(restAuthEntryPoint))
                // Read the JWT before the standard auth filter runs.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * BCrypt: per-password salt + slow work factor. Same password -> different hash,
     * and brute-forcing is expensive. Used to hash on register and verify on login.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
