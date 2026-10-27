package com.ledger.budget;

import com.ledger.common.Ids;
import com.ledger.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.time.YearMonth;

/**
 * A monthly spending limit, either overall or for one category.
 *
 * <p>A budget with a null {@link #period} is the recurring default that applies
 * to every month. A budget with a period set overrides the default for that one
 * month, which is how "December is going to be expensive" is expressed without
 * disturbing the usual figure.
 */
@Entity
@Table(name = "budget")
public class Budget {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    /** Null means this is the overall budget across all categories. */
    @Column(name = "category_id")
    private String categoryId;

    @Positive
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency = Money.INR;

    /** {@code yyyy-MM} for a single month, or null for the recurring default. */
    @Column(name = "period")
    private String period;

    /** Percentage of the budget at which the UI starts warning. */
    @Min(1)
    @Max(200)
    @Column(name = "warn_at_percent", nullable = false)
    private int warnAtPercent = 80;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Budget() {
        // for JPA
    }

    public Budget(String categoryId, Money amount, YearMonth period) {
        this.categoryId = categoryId;
        this.amountMinor = amount.minor();
        this.currency = amount.currency();
        this.period = period == null ? null : period.toString();
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

    public Money amount() {
        return Money.ofMinor(amountMinor, currency);
    }

    public void setAmount(Money money) {
        this.amountMinor = money.minor();
        this.currency = money.currency();
    }

    /** The amount at which a warning should fire. */
    public Money warningThreshold() {
        return amount().percentage(warnAtPercent);
    }

    /** True when this is the recurring default rather than a month override. */
    public boolean isDefaultBudget() {
        return period == null;
    }

    /** True when this budget applies to all spending rather than one category. */
    public boolean isOverall() {
        return categoryId == null;
    }

    public YearMonth periodAsYearMonth() {
        return period == null ? null : YearMonth.parse(period);
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
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

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public int getWarnAtPercent() {
        return warnAtPercent;
    }

    public void setWarnAtPercent(int warnAtPercent) {
        this.warnAtPercent = warnAtPercent;
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
