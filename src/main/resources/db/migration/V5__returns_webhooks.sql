-- V5: webhook deduplication + the returns table.
--
-- Two tables that have nothing to do with each other except timing: the webhook handler
-- needs its dedupe table NOW (Day 13), and the return flow needs its table soon (Day 18).
-- Grouping them costs nothing and saves a migration.

-- THE DUPLICATE-DELIVERY GUARD.
--
-- Every payment gateway delivers at-least-once. Stripe retries a webhook for up to three
-- days if it doesn't get a 2xx, which means the SAME event WILL arrive twice — after a
-- timeout, after a deploy, after our own 500. Without this table the second delivery
-- would write a second set of ledger entries and the books would stop balancing.
--
-- event_id is the PRIMARY KEY, not just an index. The uniqueness IS the feature: the
-- handler inserts before it acts, and lets the database decide whether this delivery is
-- the first one. Two simultaneous copies of the same event serialise on this key, the
-- loser inserts zero rows and returns without doing anything.
CREATE TABLE processed_webhooks (
    event_id     TEXT PRIMARY KEY,

    -- Kept for forensics: "did we ever see event evt_123, and when?" is the first
    -- question asked when a payment's state looks wrong.
    event_type   TEXT,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Supports pruning old rows once they are past the gateway's retry window, so this
-- table doesn't grow forever. Nothing schedules that yet.
CREATE INDEX idx_processed_webhook_time ON processed_webhooks (processed_at);


-- The return record, created when an owner confirms an item came back (Day 18).
--
-- Created now, with no entity, for the same reason ledger_entries was: the settlement
-- service lands on an existing schema rather than dragging a migration along with it.
CREATE TABLE returns (
    id               BIGSERIAL PRIMARY KEY,

    -- UNIQUE, not just a foreign key: an item is returned once. Without this, a retried
    -- "confirm return" would create a second record and release the deposit twice.
    booking_id       BIGINT NOT NULL UNIQUE REFERENCES bookings(id),

    condition        TEXT NOT NULL CHECK (condition IN ('OK', 'DAMAGED')),

    -- What the owner claimed from the deposit, and what goes back to the renter.
    -- The two must account for the whole deposit, which is checked at settlement time
    -- against the booking's deposit_amount.
    deposit_deducted NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (deposit_deducted >= 0),
    refund_amount    NUMERIC(12,2) NOT NULL          CHECK (refund_amount    >= 0),

    -- Free text from the owner when condition = DAMAGED. Evidence for a dispute.
    notes            TEXT,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Nothing was deducted unless something was damaged. Cheap to state here, and it
    -- makes the "owner claims damage on an OK return" bug structurally impossible.
    CONSTRAINT return_deduction_needs_damage
        CHECK (deposit_deducted = 0 OR condition = 'DAMAGED')
);
