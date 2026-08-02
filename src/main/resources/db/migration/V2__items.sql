-- V2: items table (rentable listings).
-- owner_id is a FK to users(id) — ownership lives here, checked per-resource (not by role).
-- version drives optimistic locking (JPA @Version).

CREATE TABLE items (
    id             BIGSERIAL PRIMARY KEY,
    owner_id       BIGINT NOT NULL REFERENCES users(id),
    title          TEXT NOT NULL,
    description    TEXT,
    daily_rate     NUMERIC(12,2) NOT NULL,
    deposit_amount NUMERIC(12,2) NOT NULL,
    status         TEXT NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version        BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_items_owner ON items (owner_id);
