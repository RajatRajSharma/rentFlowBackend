-- V4: payments + the double-entry ledger.
--
-- Week 3's job is the money half of the product: a payment is never taken twice, and
-- every rupee that moves is recorded as two balanced entries. Two tables, two jobs:
--
--   payments       — one row per intended money movement, deduped by idempotency_key
--   ledger_entries — the accounting record of money that ACTUALLY moved
--
-- They are deliberately separate. A payment can be PENDING or FAILED and still be a
-- real row; a ledger entry only ever exists for money that genuinely settled. Mixing
-- the two is how systems end up with books that don't balance.

CREATE TABLE payments (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      BIGINT NOT NULL REFERENCES bookings(id),

    -- The gateway's own id for this intent (Stripe: "pi_..."). Null until we've
    -- successfully called the gateway, which is exactly how the reconciliation worker
    -- (Day 18) spots a payment whose gateway call died mid-flight.
    gateway_ref     TEXT,

    -- THE DEDUPE KEY. A retried POST /bookings/{id}/pay carries the same client key,
    -- so the second attempt collides here instead of creating a second charge.
    -- Stored as "b{bookingId}:{clientKey}:{TYPE}" — scoped per booking and per money
    -- movement, so one client key can safely produce a fee row AND a deposit row while
    -- still colliding with its own retry.
    idempotency_key TEXT NOT NULL UNIQUE,

    amount          NUMERIC(12,2) NOT NULL CHECK (amount > 0),

    -- FEE      = the rental charge, owed to the owner
    -- DEPOSIT  = refundable damage hold, owed back to the renter unless claimed
    -- REFUND   = money going back out (deposit release or partial refund, Week 4)
    type            TEXT NOT NULL CHECK (type IN ('FEE', 'DEPOSIT', 'REFUND')),

    status          TEXT NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),

    -- Why the gateway said no ("card_declined"). Kept for the Day 15 failure scenarios
    -- and so support can answer "why did my payment fail?" without reading gateway logs.
    failure_reason  TEXT,

    version         BIGINT NOT NULL DEFAULT 0,   -- JPA @Version, optimistic locking
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- "Show me everything charged for this booking" — the pay endpoint and the webhook
-- handler both start here.
CREATE INDEX idx_payment_booking ON payments (booking_id);

-- The reconciliation worker (Day 18) sweeps stuck payments: WHERE status = 'PENDING'
-- AND created_at < now() - interval. Partial, because SUCCEEDED rows are the vast
-- majority and indexing them would be dead weight.
CREATE INDEX idx_payment_pending ON payments (created_at) WHERE status = 'PENDING';

-- The webhook handler (Day 13) looks a payment up by the gateway's id. Unique because
-- one gateway intent must never map to two of our rows; partial because gateway_ref is
-- null until the gateway call succeeds, and many nulls are not a collision.
CREATE UNIQUE INDEX idx_payment_gateway_ref ON payments (gateway_ref)
    WHERE gateway_ref IS NOT NULL;


-- The ledger. Append-only: rows are never updated or deleted, because an accounting
-- record you can edit is not an accounting record. A correction is a new balancing pair.
--
-- Hence no updated_at and no version column — there is nothing to update or contend on.
CREATE TABLE ledger_entries (
    id         BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings(id),

    -- Which payment caused this movement. Nullable: settlement entries on return
    -- (deposit released, damage claimed) move money between accounts without a
    -- corresponding gateway payment.
    payment_id BIGINT REFERENCES payments(id),

    -- RENTER_CASH   — money in from the renter
    -- OWNER_PAYABLE — what we owe the item's owner
    -- DEPOSIT_HELD  — the refundable hold, ours to sit on, not ours to keep
    -- RENTER_REFUND — money going back out to the renter
    account    TEXT NOT NULL
               CHECK (account IN ('RENTER_CASH', 'OWNER_PAYABLE',
                                  'DEPOSIT_HELD', 'RENTER_REFUND')),

    debit      NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (debit  >= 0),
    credit     NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (credit >= 0),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A line is one-sided by definition: it is either a debit or a credit, never both
    -- and never neither. "<>" on two booleans is XOR. This catches the single most
    -- common ledger bug — a zero-zero row that quietly balances and means nothing —
    -- at the database, not in a service someone might forget to call.
    CONSTRAINT ledger_entry_is_one_sided CHECK ((debit = 0) <> (credit = 0))
);

-- Backs the "do this booking's books balance?" query:
--   SELECT sum(debit), sum(credit) FROM ledger_entries WHERE booking_id = ?
CREATE INDEX idx_ledger_booking ON ledger_entries (booking_id);
