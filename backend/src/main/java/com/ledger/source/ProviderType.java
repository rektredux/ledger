package com.ledger.source;

/**
 * Identifies a source of transaction observations.
 *
 * <p>Only providers that are actually implemented appear here. Candidates that
 * would require approvals, platform permissions or paid services the project
 * has not obtained are documented in the README's future-integrations section
 * rather than being stubbed out as dead enum constants.
 */
public enum ProviderType {

    /**
     * The user's Gmail mailbox, read through Google's official OAuth 2.0 and
     * Gmail REST v1 APIs with the read-only scope.
     */
    GMAIL,

    /**
     * A deterministic in-process provider that replays synthetic messages.
     * Exists so the whole ingestion pipeline (parsing, matching, retry,
     * cursors, restart resumption) is testable without network access or real
     * credentials. Not a user-facing data source.
     */
    SIMULATED,

    /** A user-supplied CSV of transactions. */
    CSV,

    /**
     * A bank or card statement imported for reconciliation. Used to detect
     * transactions the automated sources missed, not as the normal entry path.
     */
    STATEMENT,

    /** The user entered the transaction themselves via the exception workflow. */
    MANUAL;

    /** True when this provider is polled on a schedule. */
    public boolean isSynchronised() {
        return this == GMAIL || this == SIMULATED;
    }

    /** True when records arrive from a one-off user-initiated file upload. */
    public boolean isFileBased() {
        return this == CSV || this == STATEMENT;
    }
}
