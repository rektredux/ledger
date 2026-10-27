package com.ledger.account;

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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * An account or payment instrument the user owns.
 *
 * <p>Accounts exist primarily so that (a) a parsed message can be attributed to
 * the right instrument via {@link #instrumentHint}, and (b) transfers between
 * two owned accounts can be recognised and excluded from spending.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @NotBlank
    @Column(name = "name", nullable = false)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private AccountType type;

    @NotBlank
    @Column(name = "currency", nullable = false)
    private String currency = Money.INR;

    @Column(name = "institution")
    private String institution;

    /**
     * The fragment of the instrument identity that appears in alerts: the last
     * four digits of a card or account number, or a UPI handle fragment.
     *
     * <p>Only ever a fragment. The application has no reason to hold a full
     * card or account number and does not ask for one.
     */
    @Column(name = "instrument_hint")
    private String instrumentHint;

    @Column(name = "opening_balance_minor", nullable = false)
    private long openingBalanceMinor;

    // --- Credit card configuration; null for other account types ------------

    @Column(name = "credit_limit_minor")
    private Long creditLimitMinor;

    @Min(1)
    @Max(31)
    @Column(name = "statement_day")
    private Integer statementDay;

    @Min(1)
    @Max(31)
    @Column(name = "due_day")
    private Integer dueDay;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // for JPA
    }

    public Account(String name, AccountType type, String currency) {
        this.name = name;
        this.type = type;
        this.currency = currency == null ? Money.INR : currency;
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

    /** The configured opening balance as money. */
    public Money openingBalance() {
        return Money.ofMinor(openingBalanceMinor, currency);
    }

    public void setOpeningBalance(Money money) {
        this.openingBalanceMinor = money.minor();
        this.currency = money.currency();
    }

    /** The credit limit, or empty when not a card or not configured. */
    public Money creditLimit() {
        return creditLimitMinor == null ? null : Money.ofMinor(creditLimitMinor, currency);
    }

    public void setCreditLimit(Money money) {
        this.creditLimitMinor = money == null ? null : money.minor();
    }

    /** True when this account's movement can never be observed automatically. */
    public boolean isManualOnly() {
        return type != null && type.isManualOnly();
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AccountType getType() {
        return type;
    }

    public void setType(AccountType type) {
        this.type = type;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getInstitution() {
        return institution;
    }

    public void setInstitution(String institution) {
        this.institution = institution;
    }

    public String getInstrumentHint() {
        return instrumentHint;
    }

    public void setInstrumentHint(String instrumentHint) {
        this.instrumentHint = instrumentHint;
    }

    public long getOpeningBalanceMinor() {
        return openingBalanceMinor;
    }

    public void setOpeningBalanceMinor(long openingBalanceMinor) {
        this.openingBalanceMinor = openingBalanceMinor;
    }

    public Long getCreditLimitMinor() {
        return creditLimitMinor;
    }

    public void setCreditLimitMinor(Long creditLimitMinor) {
        this.creditLimitMinor = creditLimitMinor;
    }

    public Integer getStatementDay() {
        return statementDay;
    }

    public void setStatementDay(Integer statementDay) {
        this.statementDay = statementDay;
    }

    public Integer getDueDay() {
        return dueDay;
    }

    public void setDueDay(Integer dueDay) {
        this.dueDay = dueDay;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
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
