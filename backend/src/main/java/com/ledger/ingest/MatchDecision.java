package com.ledger.ingest;

import com.ledger.common.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An immutable audit record of one deduplication decision.
 *
 * <p>Every time the matcher decides what to do with a source record — create a
 * transaction, attach it to an existing one, or refuse to choose — it writes a
 * row here with the strategy, the score and a human-readable rationale. Three
 * things depend on this table existing:
 *
 * <ul>
 *   <li>The review queue can explain <em>why</em> it is asking, rather than
 *       presenting two transactions with no context.</li>
 *   <li>A wrong merge can be diagnosed after the fact, which is the difference
 *       between a correctable mistake and a mysterious one.</li>
 *   <li>Changes to the matching thresholds can be evaluated against decisions
 *       the current rules already made.</li>
 * </ul>
 *
 * <p>Rows are never updated or deleted. A reversal of a decision is a new row.
 */
@Entity
@Table(name = "match_decision")
public class MatchDecision {

    /** What the matcher did. */
    public enum Decision {
        /** No acceptable candidate; a new canonical transaction was created. */
        CREATED_NEW,
        /** Attached to an existing transaction as additional evidence. */
        LINKED_EXISTING,
        /** Plausible but inconclusive; parked for a human. Nothing was merged. */
        QUEUED_FOR_REVIEW,
        /** An existing pending transaction was updated with posted details. */
        UPDATED_PENDING_TO_POSTED,
        /** Byte-identical content already ingested; recorded and skipped. */
        REJECTED_DUPLICATE_CONTENT,
        /** A user detached a source record from a transaction. */
        UNLINKED_BY_USER,
        /** A user confirmed a match or resolved a review item. */
        CONFIRMED_BY_USER
    }

    /** How the decision was reached. */
    public enum Strategy {
        /**
         * The provider supplied an identifier that uniquely names the financial
         * event (a UPI reference, a bank transaction id) and it matched. The
         * only strategy that is safe on its own.
         */
        STRONG_REFERENCE,
        /** Byte-identical content to a record already stored. */
        CONTENT_HASH,
        /** Weighted score over account, amount, date, merchant and type. */
        HEURISTIC_SCORE,
        /** A human decided. */
        USER
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @Column(name = "source_record_id", nullable = false, updatable = false)
    private String sourceRecordId;

    @Column(name = "canonical_transaction_id")
    private String canonicalTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false)
    private Decision decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, updatable = false)
    private Strategy strategy;

    /** The heuristic score, when {@link Strategy#HEURISTIC_SCORE} was used. */
    @Column(name = "score")
    private Double score;

    /**
     * Human-readable reasoning, e.g.
     * {@code "amount=exact, date=+1d, merchant=0.91, account=exact"}.
     * This is the text the review queue shows the user.
     */
    @Column(name = "rationale")
    private String rationale;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    /** {@code SYSTEM}, or {@code USER} when a human made the call. */
    @Column(name = "decided_by", nullable = false, updatable = false)
    private String decidedBy = "SYSTEM";

    protected MatchDecision() {
        // for JPA
    }

    public MatchDecision(String sourceRecordId, String canonicalTransactionId,
                         Decision decision, Strategy strategy, Double score, String rationale) {
        this.sourceRecordId = sourceRecordId;
        this.canonicalTransactionId = canonicalTransactionId;
        this.decision = decision;
        this.strategy = strategy;
        this.score = score;
        this.rationale = rationale;
    }

    /** Marks this decision as made by a human rather than the matcher. */
    public MatchDecision byUser() {
        this.decidedBy = "USER";
        return this;
    }

    @PrePersist
    void onCreate() {
        if (decidedAt == null) {
            decidedAt = Instant.now();
        }
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSourceRecordId() {
        return sourceRecordId;
    }

    public void setSourceRecordId(String sourceRecordId) {
        this.sourceRecordId = sourceRecordId;
    }

    public String getCanonicalTransactionId() {
        return canonicalTransactionId;
    }

    public void setCanonicalTransactionId(String canonicalTransactionId) {
        this.canonicalTransactionId = canonicalTransactionId;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }
}
