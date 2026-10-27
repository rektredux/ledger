package com.ledger.source;

import com.ledger.common.Ids;
import com.ledger.money.Money;
import com.ledger.txn.TransactionStatus;
import com.ledger.txn.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One observation of a (possibly already known) financial event, as reported by
 * one provider.
 *
 * <p>Source records are append-only evidence. They are what make an automatic
 * decision reversible: if the matcher wrongly merges two purchases, both
 * records survive with their extracted fields and the user can unlink them. If
 * a parser improves, old records can be re-parsed.
 *
 * <p>Every {@code extracted*} field is nullable and means exactly one thing
 * when null: <em>the source did not state this</em>. Nothing in this class is
 * ever inferred or defaulted — inference happens later, in the categorizer and
 * the matcher, where it is recorded with a confidence and an audit trail. That
 * separation is what lets the UI distinguish an extracted fact from a guess.
 *
 * <h2>What is deliberately not stored</h2>
 * The full message body is never persisted. Parsing happens in memory; what
 * survives is the extracted fields, a {@link #contentHash}, and a short
 * {@link #redactedExcerpt} that has been through
 * {@link com.ledger.common.Redaction}.
 */
@Entity
@Table(name = "source_record")
public class SourceRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private ProviderType provider;

    /**
     * The provider's stable identifier for this observation: a Gmail message
     * id, a hash of a CSV row. Unique per provider, which is the mechanism that
     * makes repeated synchronisation idempotent — re-reading the same message
     * cannot create a second record.
     */
    @NotBlank
    @Column(name = "provider_record_id", nullable = false, updatable = false)
    private String providerRecordId;

    @Column(name = "provider_connection_id")
    private String providerConnectionId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SourceRecordStatus status = SourceRecordStatus.PENDING_PARSE;

    /** The canonical event this was attributed to. Null until matched. */
    @Column(name = "canonical_transaction_id")
    private String canonicalTransactionId;

    // --- Evidence -----------------------------------------------------------

    @Column(name = "sender")
    private String sender;

    @Column(name = "subject")
    private String subject;

    @Column(name = "received_at")
    private Instant receivedAt;

    /** Short, redacted fragment retained so a parse decision is explainable. */
    @Column(name = "redacted_excerpt")
    private String redactedExcerpt;

    /** SHA-256 of the original content; lets identical content be recognised. */
    @Column(name = "content_hash")
    private String contentHash;

    // --- Extracted facts. Null means "the source did not say". --------------

    @Column(name = "extracted_amount_minor")
    private Long extractedAmountMinor;

    @Column(name = "extracted_currency")
    private String extractedCurrency;

    @Column(name = "extracted_occurred_on")
    private LocalDate extractedOccurredOn;

    @Column(name = "extracted_merchant")
    private String extractedMerchant;

    @Enumerated(EnumType.STRING)
    @Column(name = "extracted_type")
    private TransactionType extractedType;

    /**
     * Whether the source described an authorisation or a settlement.
     *
     * <p>Null when the source did not distinguish, which the pipeline treats as
     * posted. Stored rather than recomputed on demand because the message body
     * is deliberately not retained, so there would be nothing to recompute it
     * from on a retry.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "extracted_status")
    private TransactionStatus extractedStatus;

    /** Card/account tail or UPI fragment, used to attribute to an account. */
    @Column(name = "extracted_instrument_hint")
    private String extractedInstrumentHint;

    @Column(name = "extracted_reference")
    private String extractedReference;

    /** Which named parser produced the extraction. */
    @Column(name = "parser_id")
    private String parserId;

    @Column(name = "parse_confidence")
    private Double parseConfidence;

    /** Why parsing failed, when it did. Already redacted. */
    @Column(name = "parse_error")
    private String parseError;

    // --- Retry bookkeeping --------------------------------------------------

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /**
     * When this record next becomes eligible for processing. Set to a backed-off
     * future instant after a transient failure so a failing record cannot spin.
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SourceRecord() {
        // for JPA
    }

    public SourceRecord(ProviderType provider, String providerRecordId) {
        this.provider = provider;
        this.providerRecordId = providerRecordId;
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

    /** The extracted amount, or null when the source stated none. */
    public Money extractedAmount() {
        if (extractedAmountMinor == null) {
            return null;
        }
        return Money.ofMinor(extractedAmountMinor, extractedCurrency == null ? Money.INR : extractedCurrency);
    }

    public void setExtractedAmount(Money money) {
        if (money == null) {
            this.extractedAmountMinor = null;
            this.extractedCurrency = null;
        } else {
            this.extractedAmountMinor = Math.abs(money.minor());
            this.extractedCurrency = money.currency();
        }
    }

    /**
     * True when enough was extracted to stand up a canonical transaction: an
     * amount, a date and a direction. Without all three the record cannot
     * become a ledger entry and belongs in the exception queue instead.
     */
    public boolean hasMinimumFields() {
        return extractedAmountMinor != null && extractedOccurredOn != null && extractedType != null;
    }

    /** Records a processing attempt, for retry accounting. */
    public void recordAttempt() {
        this.attemptCount++;
        this.lastAttemptAt = Instant.now();
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ProviderType getProvider() {
        return provider;
    }

    public void setProvider(ProviderType provider) {
        this.provider = provider;
    }

    public String getProviderRecordId() {
        return providerRecordId;
    }

    public void setProviderRecordId(String providerRecordId) {
        this.providerRecordId = providerRecordId;
    }

    public String getProviderConnectionId() {
        return providerConnectionId;
    }

    public void setProviderConnectionId(String providerConnectionId) {
        this.providerConnectionId = providerConnectionId;
    }

    public SourceRecordStatus getStatus() {
        return status;
    }

    public void setStatus(SourceRecordStatus status) {
        this.status = status;
    }

    public String getCanonicalTransactionId() {
        return canonicalTransactionId;
    }

    public void setCanonicalTransactionId(String canonicalTransactionId) {
        this.canonicalTransactionId = canonicalTransactionId;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getRedactedExcerpt() {
        return redactedExcerpt;
    }

    public void setRedactedExcerpt(String redactedExcerpt) {
        this.redactedExcerpt = redactedExcerpt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Long getExtractedAmountMinor() {
        return extractedAmountMinor;
    }

    public void setExtractedAmountMinor(Long extractedAmountMinor) {
        this.extractedAmountMinor = extractedAmountMinor;
    }

    public String getExtractedCurrency() {
        return extractedCurrency;
    }

    public void setExtractedCurrency(String extractedCurrency) {
        this.extractedCurrency = extractedCurrency;
    }

    public LocalDate getExtractedOccurredOn() {
        return extractedOccurredOn;
    }

    public void setExtractedOccurredOn(LocalDate extractedOccurredOn) {
        this.extractedOccurredOn = extractedOccurredOn;
    }

    public String getExtractedMerchant() {
        return extractedMerchant;
    }

    public void setExtractedMerchant(String extractedMerchant) {
        this.extractedMerchant = extractedMerchant;
    }

    public TransactionType getExtractedType() {
        return extractedType;
    }

    public void setExtractedType(TransactionType extractedType) {
        this.extractedType = extractedType;
    }

    public TransactionStatus getExtractedStatus() {
        return extractedStatus;
    }

    public void setExtractedStatus(TransactionStatus extractedStatus) {
        this.extractedStatus = extractedStatus;
    }

    /** The parsed status, defaulting to posted when the source was silent. */
    public TransactionStatus effectiveStatus() {
        return extractedStatus == null ? TransactionStatus.POSTED : extractedStatus;
    }

    public String getExtractedInstrumentHint() {
        return extractedInstrumentHint;
    }

    public void setExtractedInstrumentHint(String extractedInstrumentHint) {
        this.extractedInstrumentHint = extractedInstrumentHint;
    }

    public String getExtractedReference() {
        return extractedReference;
    }

    public void setExtractedReference(String extractedReference) {
        this.extractedReference = extractedReference;
    }

    public String getParserId() {
        return parserId;
    }

    public void setParserId(String parserId) {
        this.parserId = parserId;
    }

    public Double getParseConfidence() {
        return parseConfidence;
    }

    public void setParseConfidence(Double parseConfidence) {
        this.parseConfidence = parseConfidence;
    }

    public String getParseError() {
        return parseError;
    }

    public void setParseError(String parseError) {
        this.parseError = parseError;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
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
