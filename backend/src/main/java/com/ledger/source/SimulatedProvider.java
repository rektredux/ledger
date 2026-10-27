package com.ledger.source;

import com.ledger.ingest.RawObservation;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An in-process provider that replays messages queued into it.
 *
 * <p>Exists so the entire pipeline — parsing, account attribution,
 * deduplication, the review queue, retry and backoff, cursor advancement and
 * restart resumption — can be tested deterministically with no network, no
 * credentials and no external approval. That matters for a project whose one
 * real provider needs a Google Cloud OAuth client the user has to create
 * themselves: without this, none of the ingestion logic could be verified until
 * that external setup was done.
 *
 * <p>It is not a user-facing data source and the UI does not offer to connect
 * it. It appears on the Data Sources screen only as a diagnostic when messages
 * have been queued, which in normal operation is never.
 *
 * <p>The cursor is an index into the queued list, which exercises the same
 * advance-only-after-durable-storage contract that the Gmail provider's
 * {@code historyId} does.
 */
@Component
public class SimulatedProvider implements TransactionSourceProvider {

    /** Queued observations, oldest first. Guarded by {@code this}. */
    private final List<RawObservation> queue = Collections.synchronizedList(new ArrayList<>());

    @Override
    public ProviderType type() {
        return ProviderType.SIMULATED;
    }

    @Override
    public String displayName() {
        return "Simulated feed (testing only)";
    }

    @Override
    public boolean isConfigured() {
        // Always usable: it needs no credentials. It is still not *connected*
        // unless something explicitly creates a connection for it.
        return true;
    }

    /** Queues a message to be returned by the next fetch. */
    public void enqueue(RawObservation observation) {
        queue.add(observation);
    }

    public void enqueueAll(List<RawObservation> observations) {
        queue.addAll(observations);
    }

    /** Empties the queue and resets state. Used between tests. */
    public void reset() {
        queue.clear();
    }

    public int queueSize() {
        return queue.size();
    }

    @Override
    public FetchPage fetch(ProviderConnection connection, FetchRequest request) {
        int from = parseCursor(request.cursor());
        List<RawObservation> snapshot;
        synchronized (queue) {
            snapshot = List.copyOf(queue);
        }

        if (from >= snapshot.size()) {
            return FetchPage.empty(String.valueOf(snapshot.size()));
        }

        int to = Math.min(snapshot.size(), from + Math.max(1, request.pageSize()));
        List<RawObservation> page = snapshot.subList(from, to);

        LocalDate oldest = page.stream()
                .filter(o -> o.receivedAt() != null)
                .map(o -> o.receivedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate())
                .min(LocalDate::compareTo)
                .orElse(null);

        return new FetchPage(List.copyOf(page), String.valueOf(to), to < snapshot.size(), oldest);
    }

    /**
     * A non-numeric or absent cursor starts from the beginning. Tolerating
     * garbage rather than throwing matters because a cursor can survive a
     * format change across versions, and re-reading from the start is safe
     * (ingestion is idempotent) whereas failing the sync is not.
     */
    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
