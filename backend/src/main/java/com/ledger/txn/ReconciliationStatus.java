package com.ledger.txn;

/** How well a canonical transaction is corroborated by its source evidence. */
public enum ReconciliationStatus {

    /** Seen from exactly one source and not yet corroborated or reviewed. */
    UNRECONCILED,

    /**
     * Two or more independent source records were linked to this transaction
     * automatically (for example an instant card alert and the later bank
     * statement line). This is the strongest automatic signal that the amount
     * is real and counted exactly once.
     */
    MATCHED,

    /**
     * The matcher found a plausible but not conclusive candidate and refused to
     * decide. There is an open {@code review_item} pointing here. The
     * transaction still counts toward totals; what is uncertain is whether it
     * duplicates another row, and that is surfaced rather than guessed.
     */
    NEEDS_REVIEW,

    /** A human explicitly confirmed this transaction or resolved its match. */
    CONFIRMED
}
