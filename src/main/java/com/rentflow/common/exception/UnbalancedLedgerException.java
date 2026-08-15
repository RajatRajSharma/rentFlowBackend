package com.rentflow.common.exception;

/**
 * Someone tried to post a ledger movement whose debits and credits don't agree.
 *
 * This is never a user's fault and never something a retry fixes — it means our own
 * accounting code is wrong. So it maps to a 500, and the transaction rolls back rather than
 * leaving half a movement behind. A quietly-swallowed version of this is how books stop
 * balancing without anyone noticing.
 */
public class UnbalancedLedgerException extends RuntimeException {

    public UnbalancedLedgerException(String message) {
        super(message);
    }
}
