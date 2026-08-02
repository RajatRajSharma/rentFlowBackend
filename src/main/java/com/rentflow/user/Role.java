package com.rentflow.user;

/**
 * The two account roles. Owner vs renter are *capabilities* of a USER account,
 * not separate roles — ownership is checked per-resource, not by role.
 */
public enum Role {
    USER,
    ADMIN
}
