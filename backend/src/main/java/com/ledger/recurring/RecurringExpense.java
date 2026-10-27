package com.ledger.recurring;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Instant;

/**
 * A definition of an expense the user expects to recur: rent, a subscription,
 * an insurance premium.
 *
 * <p>Crucially, a definition is <em>not</em> a transaction and never becomes one
 * on its own. It generates {@link RecurringOccurrence} rows, which are
 * forecasts. An occurrence only ever becomes linked to real spending when
 * ingestion observes a matching transaction. The application will not assert
 * that rent was paid because the first of the month arrived.
 */
@Entity
@Table(name = "recurring_expense")
public class RecurringExpense {

    public enum Cadence {
        WEEKLY,
        MONTHLY,
        YEARLY
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @NotBlank
    @Column(name = "name", nullable = false)
    private String name;

    @Positive
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency = Money.INR;

    @Column(name = "category_id")
    private String categoryId;

    @Column(name = "account_id")
    private String accountId;

    /** Expected merchant, used to match an observed transaction to this definition. */
    @Column(name = "merchant")
    private String merchant;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", nullable = false)
    private Cadence cadence = Cadence.MONTHLY;

    /**
     * Day of month for MONTHLY and YEARLY, or ISO day of week (1=Monday) for
     * WEEKLY. A day of 29-31 is clamped to the last day of short months.
     */
    @Column(name = "day_of_period", nullable = false)
    private int dayOfPeriod;

    /** Month of year, for YEARLY only. */
    @Column(name = "month_of_year")
    private Integer monthOfYear;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecurringExpense() {
        // for JPA
    }

    public RecurringExpense(String name, Money amount, Cadence cadence, int dayOfPeriod, LocalDate startsOn) {
        this.name = name;
        this.amountMinor = amount.minor();
        this.currency = amount.currency();
        this.cadence = cadence;
        this.dayOfPeriod = dayOfPeriod;
        this.startsOn = startsOn;
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

    /** True when the definition should produce occurrences covering {@code date}. */
    public boolean coversDate(LocalDate date) {
        if (!active || date.isBefore(startsOn)) {
            return false;
        }
        return endsOn == null || !date.isAfter(endsOn);
    }

    /**
     * The next due date on or after {@code from}.
     *
     * <p>Day-of-month values beyond a month's length are clamped to its last
     * day, so a definition due on the 31st fires on 28 February rather than
     * being skipped.
     */
    public LocalDate nextDueOnOrAfter(LocalDate from) {
        LocalDate anchor = from.isBefore(startsOn) ? startsOn : from;
        return switch (cadence) {
            case WEEKLY -> nextWeekly(anchor);
            case MONTHLY -> nextMonthly(anchor);
            case YEARLY -> nextYearly(anchor);
        };
    }

    private LocalDate nextWeekly(LocalDate anchor) {
        DayOfWeek target = DayOfWeek.of(Math.clamp(dayOfPeriod, 1, 7));
        LocalDate candidate = anchor;
        // At most 7 steps, so a loop is clearer here than modular arithmetic.
        for (int i = 0; i < 7; i++) {
            if (candidate.getDayOfWeek() == target) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private LocalDate nextMonthly(LocalDate anchor) {
        LocalDate candidate = clampToMonth(anchor.withDayOfMonth(1), dayOfPeriod);
        if (candidate.isBefore(anchor)) {
            candidate = clampToMonth(anchor.withDayOfMonth(1).plusMonths(1), dayOfPeriod);
        }
        return candidate;
    }

    private LocalDate nextYearly(LocalDate anchor) {
        int month = monthOfYear == null ? anchor.getMonthValue() : Math.clamp(monthOfYear, 1, 12);
        LocalDate candidate = clampToMonth(LocalDate.of(anchor.getYear(), month, 1), dayOfPeriod);
        if (candidate.isBefore(anchor)) {
            candidate = clampToMonth(LocalDate.of(anchor.getYear() + 1, month, 1), dayOfPeriod);
        }
        return candidate;
    }

    /** Sets the day of month, clamping to the given month's final day. */
    private static LocalDate clampToMonth(LocalDate firstOfMonth, int day) {
        int clamped = Math.clamp(day, 1, firstOfMonth.lengthOfMonth());
        return firstOfMonth.withDayOfMonth(clamped);
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

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getMerchant() {
        return merchant;
    }

    public void setMerchant(String merchant) {
        this.merchant = merchant;
    }

    public Cadence getCadence() {
        return cadence;
    }

    public void setCadence(Cadence cadence) {
        this.cadence = cadence;
    }

    public int getDayOfPeriod() {
        return dayOfPeriod;
    }

    public void setDayOfPeriod(int dayOfPeriod) {
        this.dayOfPeriod = dayOfPeriod;
    }

    public Integer getMonthOfYear() {
        return monthOfYear;
    }

    public void setMonthOfYear(Integer monthOfYear) {
        this.monthOfYear = monthOfYear;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public void setStartsOn(LocalDate startsOn) {
        this.startsOn = startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public void setEndsOn(LocalDate endsOn) {
        this.endsOn = endsOn;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
