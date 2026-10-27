package com.ledger.sync;

import com.ledger.source.ProviderConnection;
import com.ledger.source.ProviderConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Polls connected providers on a timer while the backend is running.
 *
 * <h2>What this can and cannot do</h2>
 * Synchronization happens server-side and does not need the browser open — the
 * UI is a view onto a ledger the backend maintains by itself. But this backend
 * runs on the user's own machine, so <em>it cannot sync while that machine is
 * off or asleep</em>. Nothing in the design hides this: the Data Sources screen
 * shows the last successful sync time, and a stale timestamp is visible as
 * exactly what it is. When the machine wakes, the next tick resumes from the
 * stored cursor and catches up.
 *
 * <p>The alternative would be hosting the backend somewhere always-on, which
 * means paid infrastructure and the user's complete financial history leaving
 * their machine. That trade is the user's to make, not something to introduce
 * silently; the README documents it as an explicit future option.
 *
 * <h2>Backoff</h2>
 * A failing connection is skipped on most ticks rather than retried every time,
 * so a provider outage does not produce a failure entry every few minutes.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    /** Sync history older than this is pruned. */
    private static final Duration HISTORY_RETENTION = Duration.ofDays(90);

    private final SyncService syncService;
    private final ProviderConnectionRepository connections;
    private final boolean enabled;

    public SyncScheduler(SyncService syncService,
                         ProviderConnectionRepository connections,
                         @Value("${ledger.sync.enabled:true}") boolean enabled) {
        this.syncService = syncService;
        this.connections = connections;
        this.enabled = enabled;
    }

    /**
     * Tidies up state left by a previous process, at startup.
     *
     * <p>Deliberately does not kick off a sync: the first scheduled tick does
     * that after {@code ledger.sync.initial-delay}, which keeps startup fast
     * and avoids competing with Flyway and connection-pool warm-up for the
     * single SQLite connection.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            syncService.closeStaleRuns();
            syncService.pruneHistory(HISTORY_RETENTION);
        } catch (RuntimeException e) {
            log.warn("Startup housekeeping failed with {}", e.getClass().getSimpleName());
        }

        if (!enabled) {
            log.info("Scheduled synchronization is disabled (ledger.sync.enabled=false)");
            return;
        }
        List<ProviderConnection> syncable = connections.findSyncable();
        if (syncable.isEmpty()) {
            log.info("No provider is connected; nothing will be synchronized until one is");
        } else {
            log.info("{} provider connection(s) will be polled on a schedule", syncable.size());
        }
    }

    /**
     * Polls every connected provider.
     *
     * <p>{@code fixedDelayString} rather than a fixed rate: the delay is
     * measured from the end of the previous run, so a slow sync cannot cause
     * runs to pile up on top of each other.
     */
    @Scheduled(fixedDelayString = "${ledger.sync.interval:PT15M}",
            initialDelayString = "${ledger.sync.initial-delay:PT30S}")
    public void pollProviders() {
        if (!enabled) {
            return;
        }
        for (ProviderConnection connection : connections.findSyncable()) {
            if (shouldSkipForBackoff(connection)) {
                continue;
            }
            try {
                syncService.sync(connection.getProvider(), SyncRun.Mode.INCREMENTAL);
            } catch (RuntimeException e) {
                log.error("Scheduled sync of {} threw {}",
                        connection.getProvider(), e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Whether to skip a connection that has been failing.
     *
     * <p>The wait doubles with each consecutive failure up to roughly an hour.
     * A provider that is down stays visible as failing without filling the
     * history with an identical error every fifteen minutes.
     */
    private boolean shouldSkipForBackoff(ProviderConnection connection) {
        int failures = connection.getConsecutiveFailures();
        if (failures == 0 || connection.getLastAttemptAt() == null) {
            return false;
        }
        long backoffMinutes = Math.min(60L, (long) Math.pow(2, Math.min(failures, 6)));
        Instant eligibleAt = connection.getLastAttemptAt().plus(Duration.ofMinutes(backoffMinutes));
        boolean skip = Instant.now().isBefore(eligibleAt);
        if (skip) {
            log.debug("Skipping {} for backoff ({} consecutive failures)",
                    connection.getProvider(), failures);
        }
        return skip;
    }
}
