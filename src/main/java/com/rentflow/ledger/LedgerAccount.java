package com.rentflow.ledger;

/**
 * The accounts money moves between. A deliberately tiny chart of accounts — four names that
 * between them describe every movement this product makes.
 *
 * <pre>
 *   fee paid       RENTER_CASH  2000 debit  │  OWNER_PAYABLE  2000 credit
 *   deposit held   RENTER_CASH  5000 debit  │  DEPOSIT_HELD   5000 credit
 *   return, damage DEPOSIT_HELD 5000 debit  │  OWNER_PAYABLE  1500 credit
 *                                           │  RENTER_REFUND  3500 credit
 * </pre>
 *
 * The distinction that matters is {@link #OWNER_PAYABLE} versus {@link #DEPOSIT_HELD}: both
 * are money sitting with us, but the first is owed to the owner and the second is owed back
 * to the renter. Collapsing them into one "cash we hold" account would make it impossible to
 * answer the only question that matters on return — how much of this is actually ours to
 * give back?
 *
 * Must stay in sync with the CHECK constraint on {@code ledger_entries.account}
 * (V4__payments_ledger.sql); a test asserts they match.
 */
public enum LedgerAccount {

    /** Money in from the renter. */
    RENTER_CASH,

    /** What we owe the item's owner — earned rental fees, plus any damage claim. */
    OWNER_PAYABLE,

    /** The refundable hold. Ours to sit on, not ours to keep. */
    DEPOSIT_HELD,

    /** Money going back out to the renter. */
    RENTER_REFUND
}
