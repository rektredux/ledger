package com.ledger.recurring;

import com.ledger.common.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One expected instance of a {@link RecurringExpense} on a specific date.
 *
 * <p>An occurrence is a <em>forecast</em>, not a transaction. It appears in the
 * "upcoming payments" list and it is deliberately excluded from every spending
 * total until {@link #matchedTransactionId} is set, which only happens when
 * real ingested evidence is found for it. Counting forecasts as spending would
 * mean the dashboard reports money the user may never have paid.
 *
 * <p>Generation is idempotent: the unique index on
 * {@code (recurring_expense_id, due_on)} means running the generator twice for
 * the same month cannot produce a second row.
 */
@Entity
@Table(name = "recurring_occurrence")
public class RecurringOccurrence {

    public enum Status {
        /** Due date is in the future, or recently passed with no evidence yet. */
        EXPECTED,

        /** A real transaction was found for it. Only this status means "paid". */
        MATCHED,

        /**
         * The due date passed by a safety margin and nothing matched. Surfaced
         * as a possible coverage gap: either the payment did not happen, or it
         * happened and no source observed it.
         */
        MISSED,

        /** The user said this one will not happen. */
        SKIPPED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @Column(name = "recurring_expense_id", nullable = false, updatable = false)
    private String recurringExpenseId;

    @Column(name = "due_on", nullable = false, updatable = false)
    private LocalDate dueOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.EXPECTED;

    @Column(name = "matched_transaction_id")
    private String matchedTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecurringOccurrence() {
        // for JPA
    }

    public RecurringOccurrence(String recurringExpenseId, LocalDate dueOn) {
        this.recurringExpenseId = recurringExpenseId;
        this.dueOn = dueOn;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void matchTo(String transactionId) {
        this.matchedTransactionId = transactionId;
        this.status = Status.MATCHED;
    }

    /** True when real spending has been attributed to this expectation. */
    public boolean isConfirmed() {
        return status == Status.MATCHED && matchedTransactionId != null;
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRecurringExpenseId() {
        return recurringExpenseId;
    }

    public void setRecurringExpenseId(String recurringExpenseId) {
        this.recurringExpenseId = recurringExpenseId;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public void setDueOn(LocalDate dueOn) {
        this.dueOn = dueOn;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getMatchedTransactionId() {
        return matchedTransactionId;
    }

    public void setMatchedTransactionId(String matchedTransactionId) {
        this.matchedTransactionId = matchedTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
