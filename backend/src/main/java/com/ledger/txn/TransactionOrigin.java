package com.ledger.txn;

/**
 * How a canonical transaction came to exist.
 *
 * <p>Reported on the dashboard so the user can see what share of their ledger
 * was collected automatically versus typed in or imported by hand. That ratio
 * is the honest answer to "is my spending total complete?".
 */
public enum TransactionOrigin {

    /** Created by the ingestion pipeline from a provider source record. */
    AUTOMATIC,

    /** Created by a CSV import, typically to backfill or reconcile. */
    CSV_IMPORT,

    /**
     * Entered by the user through the exception workflow, for a transaction no
     * supported source could observe (cash, an unsupported bank).
     */
    MANUAL,

    /**
     * Materialised from a recurring definition after evidence arrived that it
     * actually occurred. A recurring definition alone never creates one of
     * these.
     */
    RECURRING;

    /** True when no human had to transcribe this transaction. */
    public boolean isAutomatic() {
        return this == AUTOMATIC;
    }
}
