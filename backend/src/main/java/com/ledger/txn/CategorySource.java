package com.ledger.txn;

/**
 * Where a transaction's category came from.
 *
 * <p>Carried alongside the category so the UI can show the user the difference
 * between something the source stated, something the app guessed, and something
 * they decided themselves. An inferred category presented as fact is how a
 * tracker quietly loses the user's trust.
 */
public enum CategorySource {

    /** Matched a built-in or user-authored merchant rule. */
    RULE,

    /** Matched a rule the app wrote after the user corrected a similar transaction. */
    LEARNED,

    /** Set explicitly by the user on this transaction. Never overwritten automatically. */
    USER,

    /**
     * No rule matched with sufficient confidence. The transaction is still
     * stored and still counted; it appears in the review queue for
     * categorisation rather than being forced into a wrong bucket.
     */
    UNCATEGORIZED;

    /** True when the user decided this and automation must not override it. */
    public boolean isUserAuthored() {
        return this == USER;
    }

    /** True when the value is a guess that should be shown as such. */
    public boolean isInferred() {
        return this == RULE || this == LEARNED;
    }
}
