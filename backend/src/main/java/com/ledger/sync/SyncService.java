package com.ledger.sync;

import com.ledger.common.Redaction;
import com.ledger.ingest.IngestionService;
import com.ledger.ingest.RawObservation;
import com.ledger.source.ProviderConnection;
import com.ledger.source.ProviderConnectionRepository;
import com.ledger.source.ProviderType;
import com.ledger.source.TransactionSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives one synchronization run against one provider.
 *
 * <p>Separated from {@link SyncScheduler} so that a sync can be triggered three
 * ways — on a timer, by the user pressing "Sync now", or by a test — with
 * identical behaviour.
 *
 * <h2>Restart safety</h2>
 * All progress lives in the database: the cursor and backfill watermark on
 * {@link ProviderConnection}, the per-record status on each source record. The
 * process can be killed at any point and the next run continues from there. The
 * cursor is advanced only after a page has been ingested, so a crash mid-page
 * causes that page to be re-read rather than skipped — safe, because ingestion
 * is idempotent.
 *
 * <h2>Backfill and incremental in one loop</h2>
 * A first run walks backwards through history to
 * {@code ledger.sync.backfill-days}, recording how far it reached so a long
 * import resumes rather than restarting. Once the backfill is complete, runs
 * are incremental from the cursor. Both use the same fetch/ingest loop.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /**
     * Pages fetched per run, as a safety valve. A run that keeps finding more
     * work yields and resumes on the next tick instead of monopolising the
     * single database connection indefinitely.
     */
    private static final int MAX_PAGES_PER_RUN = 20;

    private final ProviderConnectionRepository connections;
    private final SyncRunRepository runs;
    private final IngestionService ingestion;
    private final Map<ProviderType, TransactionSourceProvider> providers;

    private final int backfillDays;
    private final int pageSize;

    /**
     * Guards against two runs for the same provider overlapping — a scheduled
     * tick firing while the user's manual sync is still going. SQLite has a
     * single writer, so overlapping runs would mostly serialise anyway, but
     * they would also both advance the cursor and double the work.
     */
    private final Map<ProviderType, AtomicBoolean> inFlight = new HashMap<>();

    public SyncService(ProviderConnectionRepository connections,
                       SyncRunRepository runs,
                       IngestionService ingestion,
                       List<TransactionSourceProvider> providerBeans,
                       @Value("${ledger.sync.backfill-days:180}") int backfillDays,
                       @Value("${ledger.sync.page-size:50}") int pageSize) {
        this.connections = connections;
        this.runs = runs;
        this.ingestion = ingestion;
        this.providers = new HashMap<>();
        for (TransactionSourceProvider provider : providerBeans) {
            this.providers.put(provider.type(), provider);
            this.inFlight.put(provider.type(), new AtomicBoolean(false));
        }
        this.backfillDays = backfillDays;
        this.pageSize = pageSize;
    }

    public Optional<TransactionSourceProvider> provider(ProviderType type) {
        return Optional.ofNullable(providers.get(type));
    }

    public List<TransactionSourceProvider> allProviders() {
        return List.copyOf(providers.values());
    }

    /**
     * Runs a synchronization for one provider.
     *
     * @return the completed run record, or empty when the provider is not
     *     connected or a run is already in flight
     */
    public Optional<SyncRun> sync(ProviderType type, SyncRun.Mode mode) {
        TransactionSourceProvider provider = providers.get(type);
        if (provider == null) {
            return Optional.empty();
        }

        Optional<ProviderConnection> maybeConnection = connections.findByProvider(type);
        if (maybeConnection.isEmpty()) {
            return Optional.empty();
        }
        ProviderConnection connection = maybeConnection.get();
        if (connection.getStatus() != ProviderConnection.Status.CONNECTED) {
            log.debug("Skipping sync for {}: status is {}", type, connection.getStatus());
            return Optional.empty();
        }

        AtomicBoolean lock = inFlight.computeIfAbsent(type, t -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            log.info("Sync for {} is already running; skipping this trigger", type);
            return Optional.empty();
        }

        try {
            return Optional.of(execute(provider, connection, mode));
        } finally {
            lock.set(false);
        }
    }

    private SyncRun execute(TransactionSourceProvider provider,
                            ProviderConnection connection,
                            SyncRun.Mode mode) {
        SyncRun run = runs.save(new SyncRun(connection.getId(), mode, connection.getSyncCursor()));
        String cursor = connection.getSyncCursor();

        try {
            LocalDate until = LocalDate.now();
            LocalDate since = backfillFloor(connection);

            for (int page = 0; page < MAX_PAGES_PER_RUN; page++) {
                TransactionSourceProvider.FetchPage fetched = provider.fetch(connection,
                        new TransactionSourceProvider.FetchRequest(cursor, since, until, pageSize));

                run.addExamined(fetched.observations().size());

                for (RawObservation observation : fetched.observations()) {
                    ingestion.ingest(provider.type(), connection.getId(), observation, run);
                }

                // Only now that the page is durably ingested is it safe to move
                // the cursor forward. Persisting it per page (rather than once
                // at the end) means a crash costs at most one page of rework.
                cursor = fetched.nextCursor();
                connection.setSyncCursor(cursor);
                if (fetched.oldestReached() != null) {
                    advanceBackfillWatermark(connection, fetched.oldestReached());
                }
                connections.save(connection);
                runs.save(run);

                if (!fetched.hasMore()) {
                    break;
                }
            }

            // Mark the historical import finished once it has reached the
            // configured floor, so later runs stay incremental.
            if (!connection.isBackfillComplete()
                    && connection.getBackfillThrough() != null
                    && !connection.getBackfillThrough().isAfter(since)) {
                connection.setBackfillComplete(true);
            }

            connection.recordSuccess(cursor);
            connections.save(connection);

            run.finish(cursor);
            log.info("Sync {} for {}: examined={} created={} linked={} review={} failed={}",
                    run.getStatus(), provider.type(), run.getMessagesExamined(),
                    run.getTransactionsCreated(), run.getTransactionsLinked(),
                    run.getQueuedForReview(), run.getParseFailures());

        } catch (TransactionSourceProvider.ProviderAuthException e) {
            // The grant is gone. Pause sync and ask the user to reconnect
            // rather than retrying forever while the dashboard silently shows
            // data that is no longer being updated.
            connection.setStatus(ProviderConnection.Status.NEEDS_REAUTH);
            connection.recordFailure("authorization is no longer valid; please reconnect");
            connections.save(connection);
            run.fail("authorization is no longer valid");
            log.warn("Provider {} needs reauthorization", provider.type());

        } catch (TransactionSourceProvider.ProviderTransientException e) {
            connection.recordFailure(Redaction.redact(e.getMessage()));
            if (connection.getConsecutiveFailures() >= 5) {
                connection.setStatus(ProviderConnection.Status.ERROR);
            }
            connections.save(connection);
            run.fail(Redaction.redact(e.getMessage()));
            log.warn("Transient sync failure for {} ({} consecutive)",
                    provider.type(), connection.getConsecutiveFailures());

        } catch (RuntimeException e) {
            // Unexpected. The message is deliberately not logged in full, only
            // its type, so a stack trace cannot leak message content.
            connection.recordFailure(e.getClass().getSimpleName());
            connections.save(connection);
            run.fail(e.getClass().getSimpleName());
            log.error("Unexpected sync failure for {}: {}", provider.type(), e.getClass().getSimpleName());
        }

        SyncRun saved = runs.save(run);

        // Give previously deferred records another chance now, so a transient
        // failure does not wait for new mail to arrive before being retried.
        try {
            ingestion.retryPending(pageSize);
        } catch (RuntimeException e) {
            log.warn("Retry sweep failed with {}", e.getClass().getSimpleName());
        }
        return saved;
    }

    /** The oldest date a historical import should reach. */
    private LocalDate backfillFloor(ProviderConnection connection) {
        if (connection.isBackfillComplete()) {
            // Incremental runs still need a lower bound for providers that
            // filter by date; a short window is enough once caught up.
            return LocalDate.now().minusDays(30);
        }
        return LocalDate.now().minusDays(backfillDays);
    }

    /** Records the oldest date reached, so a long import can resume. */
    private void advanceBackfillWatermark(ProviderConnection connection, LocalDate oldestReached) {
        LocalDate current = connection.getBackfillThrough();
        if (current == null || oldestReached.isBefore(current)) {
            connection.setBackfillThrough(oldestReached);
        }
    }

    /**
     * Marks runs left {@code RUNNING} by a killed process as failed.
     *
     * <p>Called at startup. Without it the health panel would show a sync that
     * has apparently been running since the machine was last shut down. The
     * ledger itself is unaffected — per-record state and the cursor are both
     * accurate — so this only corrects the run history.
     */
    @Transactional
    public int closeStaleRuns() {
        List<SyncRun> stale = runs.findStale();
        for (SyncRun run : stale) {
            run.fail("the backend stopped while this sync was running");
            runs.save(run);
        }
        if (!stale.isEmpty()) {
            log.info("Closed {} sync run(s) interrupted by a restart", stale.size());
        }
        return stale.size();
    }

    /** Trims sync history to keep the table bounded. */
    @Transactional
    public int pruneHistory(Duration keepFor) {
        return runs.deleteOlderThan(Instant.now().minus(keepFor));
    }
}
