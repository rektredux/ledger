package com.ledger.source;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SourceRecordRepository extends JpaRepository<SourceRecord, String> {

    /**
     * The idempotency check. Called before a provider record is stored; a hit
     * means this message has already been ingested and must not produce a
     * second record or a second transaction.
     */
    Optional<SourceRecord> findByProviderAndProviderRecordId(ProviderType provider, String providerRecordId);

    boolean existsByProviderAndProviderRecordId(ProviderType provider, String providerRecordId);

    /**
     * Bulk existence check so a sync run can filter an entire page of message
     * ids with one query instead of one query per message.
     */
    @Query("""
            select r.providerRecordId from SourceRecord r
            where r.provider = :provider and r.providerRecordId in :ids
            """)
    List<String> findExistingProviderRecordIds(@Param("provider") ProviderType provider,
                                               @Param("ids") List<String> ids);

    /** All observations linked to one canonical transaction: its evidence. */
    List<SourceRecord> findByCanonicalTransactionId(String canonicalTransactionId);

    /**
     * Records that have identical original content to one already stored.
     * A duplicate delivery or a forwarded copy of the same alert.
     */
    List<SourceRecord> findByContentHash(String contentHash);

    /**
     * Records awaiting processing whose backoff has elapsed.
     *
     * <p>The {@code nextAttemptAt is null} branch covers records that have
     * never been attempted; the comparison covers records that failed
     * transiently and have since become eligible again.
     */
    @Query("""
            select r from SourceRecord r
            where r.status in (com.ledger.source.SourceRecordStatus.PENDING_PARSE,
                               com.ledger.source.SourceRecordStatus.PARSED)
              and r.attemptCount < :maxAttempts
              and (r.nextAttemptAt is null or r.nextAttemptAt <= :now)
            order by r.receivedAt asc nulls last
            """)
    List<SourceRecord> findReadyForProcessing(@Param("now") Instant now,
                                              @Param("maxAttempts") int maxAttempts,
                                              Pageable pageable);

    Page<SourceRecord> findByStatusOrderByReceivedAtDesc(SourceRecordStatus status, Pageable pageable);

    long countByStatus(SourceRecordStatus status);

    long countByProvider(ProviderType provider);

    /** Records that need a human: failed parses and ambiguous matches. */
    @Query("""
            select r from SourceRecord r
            where r.status in (com.ledger.source.SourceRecordStatus.PARSE_FAILED,
                               com.ledger.source.SourceRecordStatus.NEEDS_REVIEW)
            order by r.receivedAt desc
            """)
    Page<SourceRecord> findNeedingAttention(Pageable pageable);

    /**
     * Records that exhausted their retry budget. These are a coverage gap: the
     * message existed but never became a ledger entry.
     */
    @Query("""
            select count(r) from SourceRecord r
            where r.attemptCount >= :maxAttempts
              and r.status not in (com.ledger.source.SourceRecordStatus.LINKED,
                                   com.ledger.source.SourceRecordStatus.IGNORED)
            """)
    long countExhausted(@Param("maxAttempts") int maxAttempts);
}
