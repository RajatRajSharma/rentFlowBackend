package com.rentflow.settlement;

/**
 * How the item came back. Must stay in sync with the CHECK on {@code returns.condition} (V5).
 */
public enum ReturnCondition {

    /** As it went out. The whole deposit goes back. */
    OK,

    /** The owner is claiming part of the deposit; the booking goes to DISPUTED. */
    DAMAGED
}
