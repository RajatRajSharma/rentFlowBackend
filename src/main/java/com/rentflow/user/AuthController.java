package com.rentflow.user;

import com.rentflow.security.JwtService;
import com.rentflow.user.dto.AuthResponse;
import com.rentflow.user.dto.LoginRequest;
import com.rentflow.user.dto.RegisterRequest;
import com.rentflow.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints. Thin controller: validate the request, delegate to the service,
 * map the result to a DTO. No business logic here.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)   // 201 on success
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return UserResponse.from(userService.register(request));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userService.authenticate(request.email(), request.password());
        String token = jwtService.generateToken(user);
        return AuthResponse.bearer(token, UserResponse.from(user));
    }
}
