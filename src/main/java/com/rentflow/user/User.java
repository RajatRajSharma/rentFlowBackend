package com.rentflow.user;

import com.rentflow.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user account. Maps to the `users` table created by V1__users.sql.
 * Extends Auditable for the created_at / updated_at columns.
 */
@Entity
@Table(name = "users")
public class User extends Auditable {

    // IDENTITY = the DB generates the id (BIGSERIAL), and JPA reads it back.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    // Store only the BCrypt hash — never the raw password.
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    // EnumType.STRING = store "USER"/"ADMIN" as text (readable, stable), not an ordinal int.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    // JPA requires a no-arg constructor; protected keeps it out of app code.
    protected User() {
    }

    public User(String name, String email, String passwordHash, Role role) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}
