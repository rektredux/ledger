package com.ledger.review;

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
 * An open question the pipeline could not answer on its own.
 *
 * <p>This table is the application's answer to "never silently discard an
 * ambiguous transaction". Whenever the matcher, a parser or the categorizer
 * reaches a point where guessing could corrupt the ledger, it writes a row
 * here and moves on. Nothing is dropped and nothing is merged on a coin flip.
 *
 * <p>The intent is that this queue stays short. It is an exception path, not
 * the normal workflow — if everyday purchases were landing here, the parsers
 * and rules would need fixing instead.
 */
@Entity
@Table(name = "review_item")
public class ReviewItem {

    public enum Kind {
        /**
         * A source record plausibly matches an existing transaction but not
         * conclusively. Both possibilities are preserved; no merge happened.
         */
        AMBIGUOUS_MATCH,

        /**
         * Content looked transaction-shaped but no parser could extract the
         * required fields. Shown with the redacted excerpt so the user can see
         * what coverage is missing.
         */
        PARSE_FAILURE,

        /** Stored and counted, but no category rule matched confidently. */
        UNKNOWN_CATEGORY,

        /**
         * An instrument hint in the message matches no configured account.
         * The transaction is recorded but unattributed, so account balances
         * stay honest about the gap.
         */
        UNKNOWN_ACCOUNT,

        /**
         * A statement import found a transaction the automated sources never
         * saw, or vice versa. The evidence of a coverage gap.
         */
        RECONCILIATION_GAP
    }

    public enum Status {
        OPEN,
        RESOLVED,
        /** The user judged it not worth acting on. Kept for the audit trail. */
        DISMISSED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "source_record_id")
    private String sourceRecordId;

    @Column(name = "canonical_transaction_id")
    private String canonicalTransactionId;

    /**
     * JSON array of candidate transactions with their scores and rationale, so
     * the UI can present the choice without recomputing the match.
     */
    @Column(name = "candidates_json")
    private String candidatesJson;

    @Column(name = "summary", nullable = false)
    private String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** What the user chose, for the audit trail. */
    @Column(name = "resolution")
    private String resolution;

    protected ReviewItem() {
        // for JPA
    }

    public ReviewItem(Kind kind, String summary) {
        this.kind = kind;
        this.summary = summary;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void resolve(String resolution) {
        this.status = Status.RESOLVED;
        this.resolution = resolution;
        this.resolvedAt = Instant.now();
    }

    public void dismiss(String reason) {
        this.status = Status.DISMISSED;
        this.resolution = reason;
        this.resolvedAt = Instant.now();
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
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

    public String getCandidatesJson() {
        return candidatesJson;
    }

    public void setCandidatesJson(String candidatesJson) {
        this.candidatesJson = candidatesJson;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }
}
