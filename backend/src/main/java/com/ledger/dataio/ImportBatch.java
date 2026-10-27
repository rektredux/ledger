package com.ledger.dataio;

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
 * One file import, from upload through preview to commit.
 *
 * <p>Imports are two-phase. An upload creates a {@link Status#PREVIEW} batch
 * that validates every row and records per-row errors without writing a single
 * transaction. Only an explicit confirmation moves it to
 * {@link Status#COMMITTED}. This exists so that a malformed CSV cannot quietly
 * half-import: the user sees exactly what will land, what is a duplicate and
 * what failed, before anything touches the ledger.
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    public enum Kind {
        /** A CSV of transactions to add to the ledger. */
        CSV_TRANSACTIONS,
        /**
         * A bank or card statement compared against what is already stored, to
         * find transactions the automated sources missed. Does not itself
         * create transactions unless the user accepts the gaps.
         */
        STATEMENT_RECONCILIATION,
        /** A full JSON backup being restored. */
        JSON_RESTORE
    }

    public enum Status {
        /** Validated, nothing written. Safe to abandon. */
        PREVIEW,
        COMMITTED,
        FAILED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private Kind kind;

    @Column(name = "filename")
    private String filename;

    /** The account rows are attributed to, when the file does not say. */
    @Column(name = "account_id")
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PREVIEW;

    @Column(name = "rows_total", nullable = false)
    private int rowsTotal;

    @Column(name = "rows_imported", nullable = false)
    private int rowsImported;

    /** Rows recognised as already present, which are skipped rather than added. */
    @Column(name = "rows_duplicate", nullable = false)
    private int rowsDuplicate;

    @Column(name = "rows_failed", nullable = false)
    private int rowsFailed;

    /**
     * JSON array of {@code {row, field, message}}. Every rejected row is
     * reported here; none is silently dropped.
     */
    @Column(name = "errors_json")
    private String errorsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    protected ImportBatch() {
        // for JPA
    }

    public ImportBatch(Kind kind, String filename) {
        this.kind = kind;
        this.filename = filename;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void markCommitted() {
        this.status = Status.COMMITTED;
        this.committedAt = Instant.now();
    }

    public void markFailed() {
        this.status = Status.FAILED;
    }

    /** True when every row was either imported or recognised as a duplicate. */
    public boolean isClean() {
        return rowsFailed == 0;
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

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getRowsTotal() {
        return rowsTotal;
    }

    public void setRowsTotal(int rowsTotal) {
        this.rowsTotal = rowsTotal;
    }

    public int getRowsImported() {
        return rowsImported;
    }

    public void setRowsImported(int rowsImported) {
        this.rowsImported = rowsImported;
    }

    public int getRowsDuplicate() {
        return rowsDuplicate;
    }

    public void setRowsDuplicate(int rowsDuplicate) {
        this.rowsDuplicate = rowsDuplicate;
    }

    public int getRowsFailed() {
        return rowsFailed;
    }

    public void setRowsFailed(int rowsFailed) {
        this.rowsFailed = rowsFailed;
    }

    public String getErrorsJson() {
        return errorsJson;
    }

    public void setErrorsJson(String errorsJson) {
        this.errorsJson = errorsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }

    public void setCommittedAt(Instant committedAt) {
        this.committedAt = committedAt;
    }
}
