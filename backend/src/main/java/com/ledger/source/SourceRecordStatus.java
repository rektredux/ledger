package com.ledger.source;

/**
 * Lifecycle of a single observation from a provider.
 *
 * <p>A source record is never deleted once stored. It is the evidence trail
 * that makes a wrong automatic decision correctable: if the matcher links two
 * records to one transaction incorrectly, both records still exist with their
 * extracted fields and can be unlinked. Terminal states here therefore mean
 * "no further processing", not "discarded".
 */
public enum SourceRecordStatus {

    /** Stored but not yet parsed, or awaiting a retry after a transient failure. */
    PENDING_PARSE,

    /**
     * Parsed into transaction fields but not yet attributed to a canonical
     * transaction. A transient state during a sync run.
     */
    PARSED,

    /**
     * No parser recognised the content, or a parser found the content was
     * transaction-shaped but incomplete. Surfaced in the exception queue with
     * the redacted excerpt so the user can see what was missed, because
     * silently dropping it would hide a gap in coverage.
     */
    PARSE_FAILED,

    /** Successfully attributed to a canonical transaction. The success terminal state. */
    LINKED,

    /**
     * Parsed, but the matcher could not decide between creating a new
     * transaction and linking to an existing one. An open review item points
     * here.
     */
    NEEDS_REVIEW,

    /**
     * Deliberately not a transaction: a promotional email, an OTP, a statement
     * availability notice. Recorded so the same message is not re-examined on
     * every sync, and so the user can see what was filtered.
     */
    IGNORED;

    /** True when no further automatic processing should occur. */
    public boolean isTerminal() {
        return this == LINKED || this == IGNORED;
    }

    /** True when this record is waiting on a human. */
    public boolean needsAttention() {
        return this == PARSE_FAILED || this == NEEDS_REVIEW;
    }

    /** True when the record is eligible for a parse attempt. */
    public boolean isParseable() {
        return this == PENDING_PARSE || this == PARSED;
    }
}
