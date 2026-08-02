-- V1: users table.
-- Flyway runs this on startup (files are applied in version order: V1, V2, …).
-- The filename format is Vx__description.sql — the double underscore matters.

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT NOT NULL,
    email         TEXT NOT NULL UNIQUE,          -- one account per email
    password_hash TEXT NOT NULL,                 -- BCrypt hash, never the raw password
    role          TEXT NOT NULL DEFAULT 'USER'
                  CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
