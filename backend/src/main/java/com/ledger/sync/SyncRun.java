package com.ledger.sync;

import com.ledger.common.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * One synchronization attempt, with its outcome counts.
 *
 * <p>This is the sync health history the Data Sources screen renders. Counts
 * are kept separately rather than collapsed into a single "imported" number
 * because the interesting questions are specific: did anything fail to parse,
 * did anything need review, and were new transactions created or merely linked
 * to ones already known. A run that examines 200 messages and creates 0
 * transactions is healthy if they were all already ingested, and broken if the
 * parsers stopped recognising them — the breakdown is what tells them apart.
 */
@Entity
@Table(name = "sync_run")
public class SyncRun {

    public enum Mode {
        /** Historical import reaching backwards through older messages. */
        BACKFILL,
        /** Routine incremental poll from the stored cursor. */
        INCREMENTAL,
        /** The user pressed "Sync now". */
        MANUAL
    }

    public enum Status {
        RUNNING,
        SUCCESS,
        /** Completed, but some records failed to parse or needed review. */
        PARTIAL,
        FAILED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @Column(name = "provider_connection_id", nullable = false, updatable = false)
    private String providerConnectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, updatable = false)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.RUNNING;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "messages_examined", nullable = false)
    private int messagesExamined;

    @Column(name = "records_created", nullable = false)
    private int recordsCreated;

    /** Already-seen provider records, skipped by the idempotency check. */
    @Column(name = "records_skipped", nullable = false)
    private int recordsSkipped;

    @Column(name = "transactions_created", nullable = false)
    private int transactionsCreated;

    /** Source records attached to a transaction that already existed. */
    @Column(name = "transactions_linked", nullable = false)
    private int transactionsLinked;

    @Column(name = "queued_for_review", nullable = false)
    private int queuedForReview;

    @Column(name = "parse_failures", nullable = false)
    private int parseFailures;

    /** Redacted failure message when the run itself failed. */
    @Column(name = "error")
    private String error;

    @Column(name = "cursor_before")
    private String cursorBefore;

    @Column(name = "cursor_after")
    private String cursorAfter;

    protected SyncRun() {
        // for JPA
    }

    public SyncRun(String providerConnectionId, Mode mode, String cursorBefore) {
        this.providerConnectionId = providerConnectionId;
        this.mode = mode;
        this.cursorBefore = cursorBefore;
        this.startedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    /**
     * Marks the run finished, choosing SUCCESS or PARTIAL based on whether
     * anything needed human attention.
     */
    public void finish(String cursorAfter) {
        this.cursorAfter = cursorAfter;
        this.finishedAt = Instant.now();
        this.status = (parseFailures > 0 || queuedForReview > 0) ? Status.PARTIAL : Status.SUCCESS;
    }

    public void fail(String redactedMessage) {
        this.error = redactedMessage;
        this.finishedAt = Instant.now();
        this.status = Status.FAILED;
    }

    public Duration duration() {
        Instant end = finishedAt == null ? Instant.now() : finishedAt;
        return Duration.between(startedAt, end);
    }

    // --- counters -----------------------------------------------------------

    public void addExamined(int n) {
        this.messagesExamined += n;
    }

    public void incrementRecordsCreated() {
        this.recordsCreated++;
    }

    public void incrementRecordsSkipped() {
        this.recordsSkipped++;
    }

    public void incrementTransactionsCreated() {
        this.transactionsCreated++;
    }

    public void incrementTransactionsLinked() {
        this.transactionsLinked++;
    }

    public void incrementQueuedForReview() {
        this.queuedForReview++;
    }

    public void incrementParseFailures() {
        this.parseFailures++;
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProviderConnectionId() {
        return providerConnectionId;
    }

    public void setProviderConnectionId(String providerConnectionId) {
        this.providerConnectionId = providerConnectionId;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public int getMessagesExamined() {
        return messagesExamined;
    }

    public void setMessagesExamined(int messagesExamined) {
        this.messagesExamined = messagesExamined;
    }

    public int getRecordsCreated() {
        return recordsCreated;
    }

    public void setRecordsCreated(int recordsCreated) {
        this.recordsCreated = recordsCreated;
    }

    public int getRecordsSkipped() {
        return recordsSkipped;
    }

    public void setRecordsSkipped(int recordsSkipped) {
        this.recordsSkipped = recordsSkipped;
    }

    public int getTransactionsCreated() {
        return transactionsCreated;
    }

    public void setTransactionsCreated(int transactionsCreated) {
        this.transactionsCreated = transactionsCreated;
    }

    public int getTransactionsLinked() {
        return transactionsLinked;
    }

    public void setTransactionsLinked(int transactionsLinked) {
        this.transactionsLinked = transactionsLinked;
    }

    public int getQueuedForReview() {
        return queuedForReview;
    }

    public void setQueuedForReview(int queuedForReview) {
        this.queuedForReview = queuedForReview;
    }

    public int getParseFailures() {
        return parseFailures;
    }

    public void setParseFailures(int parseFailures) {
        this.parseFailures = parseFailures;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getCursorBefore() {
        return cursorBefore;
    }

    public void setCursorBefore(String cursorBefore) {
        this.cursorBefore = cursorBefore;
    }

    public String getCursorAfter() {
        return cursorAfter;
    }

    public void setCursorAfter(String cursorAfter) {
        this.cursorAfter = cursorAfter;
    }
}
