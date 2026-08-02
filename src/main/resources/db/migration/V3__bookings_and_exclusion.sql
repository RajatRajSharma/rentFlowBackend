-- V3: bookings table + the no-overlap guarantee.
--
-- This is the heart of the product. Three defences stop a double-booking; this file
-- holds the last and strongest one — the database itself refuses to store overlapping
-- active bookings for the same item, even if every line of application code is wrong.

-- btree_gist lets a GiST index mix a plain equality column (item_id, a bigint) with a
-- range column. Without it, EXCLUDE could not combine "same item" AND "overlapping dates".
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE bookings (
    id             BIGSERIAL PRIMARY KEY,
    item_id        BIGINT NOT NULL REFERENCES items(id),
    renter_id      BIGINT NOT NULL REFERENCES users(id),
    start_date     DATE NOT NULL,
    end_date       DATE NOT NULL,          -- inclusive: a 1st→1st booking is one day

    status         TEXT NOT NULL
                   CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'ACTIVE',
                                     'RETURNED', 'CLOSED', 'DISPUTED',
                                     'PAYMENT_FAILED', 'CANCELLED')),

    -- Prices are SNAPSHOT at booking time. If the owner later edits the item's rate,
    -- an existing booking keeps the price the renter agreed to.
    total_amount   NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    deposit_amount NUMERIC(12,2) NOT NULL CHECK (deposit_amount >= 0),

    version        BIGINT NOT NULL DEFAULT 0,   -- JPA @Version, optimistic locking
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT booking_dates_ordered CHECK (end_date >= start_date)
);

-- Supports the overlap query: filter by item, then range-scan the dates.
CREATE INDEX idx_booking_availability ON bookings (item_id, start_date, end_date);

-- Supports GET /bookings/me.
CREATE INDEX idx_booking_renter ON bookings (renter_id);

-- THE GUARANTEE.
-- Reject any INSERT/UPDATE where the same item already has a booking whose date range
-- overlaps — but only among statuses that actually occupy the item. A CANCELLED or
-- PAYMENT_FAILED booking frees its dates again, which is why this is a PARTIAL constraint.
--
--   item_id WITH =    -> same item
--   daterange(...) && -> ranges overlap
--   '[]'              -> both ends inclusive, matching how we quote and charge days
--
-- A violation surfaces as a DataIntegrityViolationException, which BookingService
-- translates into a clean 409 Conflict.
ALTER TABLE bookings
    ADD CONSTRAINT no_overlapping_active_bookings
    EXCLUDE USING gist (
        item_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&
    ) WHERE (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'ACTIVE'));
