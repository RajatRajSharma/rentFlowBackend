package com.rentflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEMPORARY security config.
 *
 * The Spring Security starter locks down every endpoint by default. Until we build
 * real JWT auth (Week 1, Day 4), this permits all requests so we can exercise the
 * health/version endpoints. We will REPLACE this with route rules + a JWT filter later.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF protection matters for browser form logins; our API is token-based, so off for now.
                .csrf(csrf -> csrf.disable())
                // TODO(Day 4): replace permitAll with real public/protected route rules + JWT filter.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
