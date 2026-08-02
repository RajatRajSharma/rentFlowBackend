package com.rentflow.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Data access for users. By extending JpaRepository we get save/findById/findAll/etc.
 * for free. Spring Data implements the two methods below from their NAMES — no SQL needed:
 *   existsByEmail  -> SELECT count(*) ... WHERE email = ?
 *   findByEmail    -> SELECT ...       WHERE email = ?
 */
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);
}
