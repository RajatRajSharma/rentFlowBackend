package com.rentflow.ledger;

import java.math.BigDecimal;

/**
 * Total debits and total credits for a booking — the answer to "do the books balance?"
 *
 * The whole value of double-entry is that this is checkable rather than believable: if the
 * two sides ever differ, money has been invented or destroyed and we can say so precisely,
 * instead of discovering it months later from a bank statement.
 */
public record LedgerBalance(BigDecimal totalDebit, BigDecimal totalCredit) {

    /**
     * {@code compareTo}, not {@code equals}: BigDecimal's equals compares scale too, so
     * 2000.00 and 2000.000 would come out unequal despite being the same amount. That is
     * exactly the kind of false alarm that trains people to ignore a balance check.
     */
    public boolean isBalanced() {
        return totalDebit.compareTo(totalCredit) == 0;
    }

    /** How far out we are. Zero when balanced; signed so the direction is visible. */
    public BigDecimal drift() {
        return totalDebit.subtract(totalCredit);
    }
}
