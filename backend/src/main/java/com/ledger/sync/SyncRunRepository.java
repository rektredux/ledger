package com.ledger.sync;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SyncRunRepository extends JpaRepository<SyncRun, String> {

    List<SyncRun> findByProviderConnectionIdOrderByStartedAtDesc(String providerConnectionId, Pageable pageable);

    Optional<SyncRun> findFirstByProviderConnectionIdOrderByStartedAtDesc(String providerConnectionId);

    Optional<SyncRun> findFirstByProviderConnectionIdAndStatusOrderByStartedAtDesc(
            String providerConnectionId, SyncRun.Status status);

    /**
     * Runs left in RUNNING state, which means the process died mid-sync.
     *
     * <p>Marked FAILED on the next startup so the health panel does not show a
     * sync that has been "running" since last week. The underlying data is
     * unaffected: ingestion is idempotent and the cursor was only advanced for
     * pages that were durably stored.
     */
    @Query("select r from SyncRun r where r.status = :running")
    List<SyncRun> findStale(@Param("running") SyncRun.Status running);

    default List<SyncRun> findStale() {
        return findStale(SyncRun.Status.RUNNING);
    }

    /** Trims history so this table does not grow without bound. */
    @Modifying
    @Query("delete from SyncRun r where r.startedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
