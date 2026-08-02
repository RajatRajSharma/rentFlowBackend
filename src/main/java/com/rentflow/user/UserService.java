package com.rentflow.user;

import com.rentflow.common.exception.DuplicateEmailException;
import com.rentflow.common.exception.InvalidCredentialsException;
import com.rentflow.user.dto.RegisterRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for accounts. The service is where logic + transactions live
 * (fat service); the controller stays thin and the repository stays dumb.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // Constructor injection: Spring passes in the beans it manages.
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(RegisterRequest request) {
        // Reject duplicates early with a clean domain exception.
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }
        // Hash the password — the DB only ever sees the BCrypt hash.
        String passwordHash = passwordEncoder.encode(request.password());

        User user = new User(request.name(), request.email(), passwordHash, Role.USER);
        return userRepository.save(user);
    }

    /**
     * Verify email + password for login. Returns the user on success; throws
     * InvalidCredentialsException (→ 401) on either a missing user or a wrong password.
     */
    @Transactional(readOnly = true)
    public User authenticate(String email, String rawPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return user;
    }
}
