package com.ledger.txn;

import com.ledger.common.Ids;
import com.ledger.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One real financial event.
 *
 * <p>This is the ledger. It is deliberately separate from
 * {@code SourceRecord}, which is one <em>observation</em> of an event. A single
 * purchase routinely generates three observations — the merchant's receipt, the
 * bank's instant alert, and a line on the monthly statement — and all three
 * link to one row here. Collapsing the two concepts is what makes naive
 * trackers report the same coffee three times.
 *
 * <p>{@link #amountMinor} is always a positive magnitude; direction comes from
 * {@link #type}. See {@link TransactionType} for the accounting rules and
 * {@code LedgerMath} for how totals are derived.
 */
@Entity
@Table(name = "canonical_transaction")
public class CanonicalTransaction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @PositiveOrZero
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency = Money.INR;

    /** The date the event occurred, in the user's local terms. */
    @NotNull
    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    /** Time of day, only when the source actually stated one. */
    @Column(name = "occurred_at_time")
    private LocalTime occurredAtTime;

    /** Set when a pending transaction later settles. */
    @Column(name = "posted_on")
    private LocalDate postedOn;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private TransactionType type;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionStatus status = TransactionStatus.POSTED;

    /**
     * The merchant string exactly as the source wrote it, preserved verbatim.
     * Kept because normalisation is lossy and occasionally wrong, and the raw
     * value is the only way to diagnose that.
     */
    @Column(name = "merchant_raw")
    private String merchantRaw;

    /** The normalised, display-ready merchant name. */
    @Column(name = "merchant")
    private String merchant;

    @Column(name = "category_id")
    private String categoryId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "category_source", nullable = false)
    private CategorySource categorySource = CategorySource.UNCATEGORIZED;

    /**
     * Confidence in {@link #categoryId} between 0 and 1. Exactly 1 when the
     * user set it. Surfaced in the UI so a guess never masquerades as a fact.
     */
    @Column(name = "category_confidence", nullable = false)
    private double categoryConfidence;

    /**
     * The account money moved from (for outflows) or into (for inflows). For
     * {@link TransactionType#TRANSFER} and
     * {@link TransactionType#CARD_PAYMENT} this is the funding side.
     */
    @Column(name = "account_id")
    private String accountId;

    /** The other side of a transfer or card payment. Null otherwise. */
    @Column(name = "counter_account_id")
    private String counterAccountId;

    /** Derived from the source; not user-authored. */
    @Column(name = "description")
    private String description;

    /** User-authored only. Never written by a parser. */
    @Column(name = "notes")
    private String notes;

    /**
     * The provider's own identifier for the event: a UPI reference, a bank
     * transaction id. When present this is the strongest deduplication signal
     * available, and the schema enforces it unique per account.
     */
    @Column(name = "external_reference")
    private String externalReference;

    /** The purchase this refund returns money for, when identifiable. */
    @Column(name = "refund_of_id")
    private String refundOfId;

    /** The transaction this reversal cancels. */
    @Column(name = "reverses_id")
    private String reversesId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false)
    private ReconciliationStatus reconciliationStatus = ReconciliationStatus.UNRECONCILED;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false)
    private TransactionOrigin origin = TransactionOrigin.AUTOMATIC;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CanonicalTransaction() {
        // for JPA
    }

    public CanonicalTransaction(Money amount, LocalDate occurredOn, TransactionType type) {
        setAmount(amount);
        this.occurredOn = occurredOn;
        this.type = type;
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

    /** The positive magnitude of this transaction. */
    public Money amount() {
        return Money.ofMinor(amountMinor, currency);
    }

    /**
     * Sets the amount, storing the magnitude only.
     *
     * <p>A negative input is stored as its absolute value rather than rejected,
     * because CSV files and some providers express debits as negative numbers.
     * The direction those negatives were trying to convey must be supplied via
     * {@link #type}, which the importer is responsible for determining.
     */
    public void setAmount(Money money) {
        this.amountMinor = Math.abs(money.minor());
        this.currency = money.currency();
    }

    /**
     * The effect on the primary account's balance, signed.
     *
     * <p>For a reversal this is the <em>declared</em> direction; the true
     * direction depends on what is being reversed and is resolved by
     * {@code LedgerMath}, which has access to the referenced transaction.
     */
    public Money signedAmountForPrimaryAccount() {
        return Money.ofMinor(amountMinor * type.primarySign(), currency);
    }

    /** True when the user has explicitly chosen this category. */
    public boolean hasUserCategory() {
        return categorySource != null && categorySource.isUserAuthored();
    }

    /** True when this transaction contributes to any reported total. */
    public boolean countsTowardTotals() {
        return status != null && status.countsTowardTotals();
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public void setOccurredOn(LocalDate occurredOn) {
        this.occurredOn = occurredOn;
    }

    public LocalTime getOccurredAtTime() {
        return occurredAtTime;
    }

    public void setOccurredAtTime(LocalTime occurredAtTime) {
        this.occurredAtTime = occurredAtTime;
    }

    public LocalDate getPostedOn() {
        return postedOn;
    }

    public void setPostedOn(LocalDate postedOn) {
        this.postedOn = postedOn;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getMerchantRaw() {
        return merchantRaw;
    }

    public void setMerchantRaw(String merchantRaw) {
        this.merchantRaw = merchantRaw;
    }

    public String getMerchant() {
        return merchant;
    }

    public void setMerchant(String merchant) {
        this.merchant = merchant;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public CategorySource getCategorySource() {
        return categorySource;
    }

    public void setCategorySource(CategorySource categorySource) {
        this.categorySource = categorySource;
    }

    public double getCategoryConfidence() {
        return categoryConfidence;
    }

    public void setCategoryConfidence(double categoryConfidence) {
        this.categoryConfidence = categoryConfidence;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getCounterAccountId() {
        return counterAccountId;
    }

    public void setCounterAccountId(String counterAccountId) {
        this.counterAccountId = counterAccountId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(String externalReference) {
        this.externalReference = externalReference;
    }

    public String getRefundOfId() {
        return refundOfId;
    }

    public void setRefundOfId(String refundOfId) {
        this.refundOfId = refundOfId;
    }

    public String getReversesId() {
        return reversesId;
    }

    public void setReversesId(String reversesId) {
        this.reversesId = reversesId;
    }

    public ReconciliationStatus getReconciliationStatus() {
        return reconciliationStatus;
    }

    public void setReconciliationStatus(ReconciliationStatus reconciliationStatus) {
        this.reconciliationStatus = reconciliationStatus;
    }

    public TransactionOrigin getOrigin() {
        return origin;
    }

    public void setOrigin(TransactionOrigin origin) {
        this.origin = origin;
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
