package com.ledger.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchDecisionRepository extends JpaRepository<MatchDecision, String> {

    List<MatchDecision> findBySourceRecordIdOrderByDecidedAtAsc(String sourceRecordId);

    /** The full decision history for a transaction, oldest first. */
    List<MatchDecision> findByCanonicalTransactionIdOrderByDecidedAtAsc(String canonicalTransactionId);

    long countByDecision(MatchDecision.Decision decision);
}
