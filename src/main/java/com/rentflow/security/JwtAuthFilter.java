package com.rentflow.security;

import com.rentflow.user.Role;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Runs once per request. If there's a valid "Authorization: Bearer <token>" header,
 * it decodes the JWT, builds an AuthenticatedUser, and puts it into the SecurityContext
 * — which is how Spring Security knows the request is authenticated and who the caller is.
 * No header / bad token → we simply don't authenticate, and protected routes return 401/403.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                Long uid = ((Number) claims.get("uid")).longValue();
                String email = claims.getSubject();
                Role role = Role.valueOf(claims.get("role", String.class));

                AuthenticatedUser principal = new AuthenticatedUser(uid, email, role);
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
                var authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ex) {
                // Invalid/expired token: leave the request unauthenticated. Don't throw here.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
