package com.ledger.txn;

/**
 * Settlement state of a transaction, where the source distinguishes it.
 *
 * <p>Many Indian bank and card alerts arrive as an authorisation first and a
 * posted amount later, sometimes for a slightly different amount (tips, fuel
 * holds, foreign currency settlement). Modelling that explicitly lets a later
 * posted record update the existing pending transaction instead of creating a
 * second one.
 */
public enum TransactionStatus {

    /**
     * Authorised but not yet settled. The amount may still change. Included in
     * spending totals but flagged in the UI, because excluding it would make
     * today's spending look wrong to the user who just made the purchase.
     */
    PENDING,

    /** Settled. The default for sources that do not distinguish. */
    POSTED,

    /**
     * Cancelled outright and carrying no financial effect. Excluded from all
     * totals. Used when an authorisation expires without posting.
     */
    VOID;

    /** True when the transaction should contribute to reported totals. */
    public boolean countsTowardTotals() {
        return this != VOID;
    }

    public static TransactionStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return POSTED;
        }
        return switch (raw.trim().toUpperCase()) {
            case "PENDING", "AUTHORISED", "AUTHORIZED", "HOLD" -> PENDING;
            case "VOID", "VOIDED", "CANCELLED", "CANCELED", "EXPIRED" -> VOID;
            default -> POSTED;
        };
    }
}
