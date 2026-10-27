package com.ledger.source;

import com.ledger.ingest.RawObservation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A source of transaction observations.
 *
 * <p>Implementations deal with authorization, pagination and cursors. They know
 * nothing about parsing, categorisation or deduplication — they hand
 * {@link RawObservation}s to the ingestion pipeline and report where they got
 * to. That boundary is what lets a new provider reuse every existing parser and
 * the whole matching engine.
 *
 * <h2>Adding a provider</h2>
 * Implement this interface as a Spring bean. The scheduler discovers it, the
 * Data Sources screen lists it, and ingestion, deduplication, retry and the
 * review queue all work unchanged. The future integrations the README discusses
 * — an official bank or card API, a consent-based account aggregator, an
 * Android SMS companion, another mail provider, statement files — all fit here.
 * None of them is stubbed out: an unimplemented provider is documented, not
 * represented by dead code.
 *
 * <h2>Cursor contract</h2>
 * A provider must only advance its cursor past observations the caller has
 * durably stored. {@link FetchPage#nextCursor()} is persisted after the page is
 * ingested, never before. Getting this backwards would mean a crash mid-page
 * silently skips transactions, which is far worse than re-reading a page —
 * re-reading is free, because ingestion is idempotent.
 */
public interface TransactionSourceProvider {

    /** Which provider this is. */
    ProviderType type();

    /** Name for the Data Sources screen. */
    String displayName();

    /**
     * Whether the deployment has the credentials this provider needs.
     *
     * <p>False means the UI shows "not configured" with setup instructions
     * rather than a connect button that cannot work. This is the distinction
     * between "you have not connected your mailbox" and "this installation has
     * no OAuth client configured".
     */
    boolean isConfigured();

    /**
     * Whether connecting requires an interactive OAuth consent round trip.
     * Providers that do expose {@link #authorizationUrl} and a callback.
     */
    default boolean requiresOAuth() {
        return false;
    }

    /** The URL to send the user to in order to grant access. */
    default Optional<String> authorizationUrl(String state) {
        return Optional.empty();
    }

    /**
     * One page of observations.
     *
     * @param observations the messages, oldest first
     * @param nextCursor   cursor to persist once these are ingested; null when
     *     the provider has no incremental cursor
     * @param hasMore      whether another page is immediately available
     * @param oldestReached the oldest date covered, for backfill bookkeeping
     */
    record FetchPage(List<RawObservation> observations,
                     String nextCursor,
                     boolean hasMore,
                     LocalDate oldestReached) {

        public static FetchPage empty(String cursor) {
            return new FetchPage(List.of(), cursor, false, null);
        }

        public boolean isEmpty() {
            return observations.isEmpty();
        }
    }

    /**
     * What to fetch.
     *
     * @param cursor    the stored incremental cursor, or null for a first run
     * @param since     earliest date of interest, for a backfill
     * @param until     latest date of interest
     * @param pageSize  maximum observations to return
     */
    record FetchRequest(String cursor, LocalDate since, LocalDate until, int pageSize) {
    }

    /**
     * Fetches the next page of observations.
     *
     * @throws ProviderAuthException when the stored credentials are no longer
     *     valid and the user must reconnect. Distinguished from other failures
     *     so the connection is marked {@code NEEDS_REAUTH} and the user is
     *     asked to act, rather than the app retrying forever and showing stale
     *     data as current.
     * @throws ProviderTransientException for a rate limit, timeout or 5xx,
     *     which should be retried with backoff.
     */
    FetchPage fetch(ProviderConnection connection, FetchRequest request)
            throws ProviderAuthException, ProviderTransientException;

    /**
     * Revokes access and clears stored credentials.
     *
     * <p>Implementations should attempt to revoke the token with the provider,
     * not merely forget it locally: a token the app has forgotten but which is
     * still valid remains a live grant on the user's account.
     */
    default void disconnect(ProviderConnection connection) {
        connection.clearTokens();
        connection.setStatus(ProviderConnection.Status.DISCONNECTED);
    }

    /** Stored credentials are invalid; the user must reconnect. */
    class ProviderAuthException extends Exception {
        public ProviderAuthException(String message) {
            super(message);
        }

        public ProviderAuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A temporary failure worth retrying. */
    class ProviderTransientException extends Exception {
        public ProviderTransientException(String message) {
            super(message);
        }

        public ProviderTransientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
