package com.rentflow.payment;

/**
 * What kind of money movement a {@link Payment} row represents.
 *
 * A single booking normally produces two of these at pay time — the rental FEE and the
 * refundable DEPOSIT — because they behave completely differently afterwards. The fee is
 * earned and owed to the owner; the deposit is held and, unless damage is claimed, given
 * back. Charging them as one lump sum would make the return settlement impossible to
 * reason about.
 */
public enum PaymentType {

    /** The rental charge. Earned by the owner once the booking completes. */
    FEE,

    /** Refundable damage hold. Ours to hold, not ours to keep. */
    DEPOSIT,

    /** Money going back out — deposit release or partial refund after a damage claim. */
    REFUND
}
