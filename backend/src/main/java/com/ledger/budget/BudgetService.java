package com.ledger.budget;

import com.ledger.category.CategoryService;
import com.ledger.money.Money;
import com.ledger.report.DateRange;
import com.ledger.txn.CanonicalTransactionRepository;
import com.ledger.txn.LedgerMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Monthly budgets and progress against them.
 *
 * <p>Deliberately small. Budgets are a reporting overlay on the ledger, not a
 * second source of truth: nothing here creates or modifies a transaction, and
 * spending is always read back from the same queries the dashboard uses, so a
 * budget can never disagree with the spending figure next to it.
 *
 * <p>A budget scoped to a specific {@code yyyy-MM} overrides the recurring
 * default for that month, which is how an unusual month is handled without
 * disturbing the normal figure.
 */
@Service
public class BudgetService {

    private final BudgetRepository budgets;
    private final CanonicalTransactionRepository transactions;
    private final CategoryService categories;

    public BudgetService(BudgetRepository budgets,
                         CanonicalTransactionRepository transactions,
                         CategoryService categories) {
        this.budgets = budgets;
        this.transactions = transactions;
        this.categories = categories;
    }

    /**
     * Progress against one budget.
     *
     * @param categoryId   null for the overall budget
     * @param usedPercent  may exceed 100 when overspent
     * @param remainingMinor negative when overspent, which is shown as such
     *     rather than clamped to zero
     * @param warning      spending has passed the configured threshold
     * @param exceeded     spending has passed the budget itself
     */
    public record BudgetProgress(String budgetId,
                                 String categoryId,
                                 String categoryName,
                                 long budgetMinor,
                                 long spentMinor,
                                 long remainingMinor,
                                 int usedPercent,
                                 int warnAtPercent,
                                 boolean warning,
                                 boolean exceeded,
                                 String currency,
                                 boolean isOverall,
                                 boolean isMonthOverride) {
    }

    /**
     * Progress for every budget in force for the month.
     *
     * <p>A month-specific budget shadows the recurring default for the same
     * scope, so each scope appears exactly once.
     */
    public List<BudgetProgress> progressFor(YearMonth month) {
        DateRange range = DateRange.month(month);
        String period = month.toString();

        List<Budget> applicable = budgets.findApplicable(period);

        // Month-specific rows win over defaults for the same category scope.
        List<Budget> effective = new ArrayList<>();
        for (Budget candidate : applicable) {
            if (candidate.getPeriod() != null) {
                effective.add(candidate);
            }
        }
        for (Budget candidate : applicable) {
            if (candidate.getPeriod() == null && !hasScope(effective, candidate.getCategoryId())) {
                effective.add(candidate);
            }
        }

        List<BudgetProgress> progress = new ArrayList<>();
        for (Budget budget : effective) {
            progress.add(toProgress(budget, range));
        }
        // Overall first, then largest budget first.
        progress.sort((a, b) -> {
            if (a.isOverall() != b.isOverall()) {
                return a.isOverall() ? -1 : 1;
            }
            return Long.compare(b.budgetMinor(), a.budgetMinor());
        });
        return progress;
    }

    private static boolean hasScope(List<Budget> budgets, String categoryId) {
        for (Budget budget : budgets) {
            if (java.util.Objects.equals(budget.getCategoryId(), categoryId)) {
                return true;
            }
        }
        return false;
    }

    private BudgetProgress toProgress(Budget budget, DateRange range) {
        long spent = budget.isOverall()
                ? transactions.sumSpendingMinor(range.from(), range.to())
                        - transactions.sumSpendingOffsetsMinor(range.from(), range.to())
                // Subcategory spending rolls up into its parent's budget, so a
                // budget on "Food & Dining" covers Groceries and Restaurants.
                : transactions.sumSpendingForCategoryTree(
                        budget.getCategoryId(), range.from(), range.to());

        Money budgetMoney = budget.amount();
        Money spentMoney = Money.ofMinor(spent, budget.getCurrency());
        int usedPercent = LedgerMath.budgetUsedPercent(spentMoney, budgetMoney);

        return new BudgetProgress(
                budget.getId(),
                budget.getCategoryId(),
                budget.isOverall() ? "All spending" : categories.displayPath(budget.getCategoryId()),
                budgetMoney.minor(),
                spent,
                budgetMoney.minor() - spent,
                usedPercent,
                budget.getWarnAtPercent(),
                usedPercent >= budget.getWarnAtPercent() && usedPercent < 100,
                usedPercent >= 100,
                budget.getCurrency(),
                budget.isOverall(),
                budget.getPeriod() != null);
    }

    // --- Writes -------------------------------------------------------------

    /**
     * Creates or replaces the budget for a scope.
     *
     * <p>Upsert rather than insert, because the unique index on
     * {@code (category, period)} means a second budget for the same scope is
     * meaningless; the user setting a figure again means they want to change
     * it.
     */
    @Transactional
    public Budget upsert(String categoryId, Money amount, YearMonth period, int warnAtPercent) {
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("a budget must be a positive amount");
        }
        if (categoryId != null && categories.find(categoryId).isEmpty()) {
            throw new IllegalArgumentException("unknown category: " + categoryId);
        }
        String periodKey = period == null ? null : period.toString();

        Optional<Budget> existing = categoryId == null
                ? (periodKey == null ? budgets.findOverallDefault() : budgets.findOverallForPeriod(periodKey))
                : budgets.findByCategoryIdAndPeriod(categoryId, periodKey);

        Budget budget = existing.orElseGet(() -> new Budget(categoryId, amount, period));
        budget.setAmount(amount);
        budget.setWarnAtPercent(Math.clamp(warnAtPercent, 1, 200));
        return budgets.save(budget);
    }

    @Transactional
    public void delete(String id) {
        Budget budget = budgets.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown budget: " + id));
        budgets.delete(budget);
    }

    public List<Budget> findAll() {
        return budgets.findAll();
    }

    /** Budgets at or past their warning threshold this month. */
    public List<BudgetProgress> alertsFor(LocalDate today) {
        List<BudgetProgress> alerts = new ArrayList<>();
        for (BudgetProgress progress : progressFor(YearMonth.from(today))) {
            if (progress.warning() || progress.exceeded()) {
                alerts.add(progress);
            }
        }
        return alerts;
    }
}
