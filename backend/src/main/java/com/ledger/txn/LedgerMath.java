package com.ledger.txn;

import com.ledger.account.Account;
import com.ledger.money.Money;

import java.util.function.Function;

/**
 * The accounting rules, in one place.
 *
 * <p>Everything here is a pure function of its arguments so the rules can be
 * unit-tested without a database. The repository layer computes the same
 * arithmetic in SQL for performance; these methods are the readable
 * specification of what that SQL is supposed to mean, and the tests assert both
 * agree.
 */
public final class LedgerMath {

    private LedgerMath() {
    }

    /**
     * The effect of a transaction on the balance of {@code accountId}.
     *
     * <p>Returns zero when the transaction does not touch that account. The
     * signs follow from {@link TransactionType}: for the primary account, the
     * type's own direction; for the counter account of a transfer or card
     * payment, the opposite. That opposition is what makes a transfer net to
     * zero across the pair rather than quietly creating or destroying money.
     *
     * @param resolveReversalTarget looks up the transaction a reversal
     *     cancels, so the reversal's direction can be taken as the exact
     *     opposite of the original rather than assumed. May return null when
     *     the original is unknown, in which case the declared direction of
     *     {@link TransactionType#REVERSAL} is used.
     */
    public static Money balanceEffect(CanonicalTransaction txn,
                                      String accountId,
                                      Function<String, CanonicalTransaction> resolveReversalTarget) {
        Money zero = Money.zero(txn.getCurrency());
        if (!txn.countsTowardTotals() || accountId == null) {
            return zero;
        }

        boolean isPrimary = accountId.equals(txn.getAccountId());
        boolean isCounter = accountId.equals(txn.getCounterAccountId());
        if (!isPrimary && !isCounter) {
            return zero;
        }

        int sign;
        if (isPrimary) {
            sign = effectiveDirection(txn, resolveReversalTarget);
        } else {
            // The counter account only participates in internal movements, and
            // always as the mirror image of the primary side.
            if (!txn.getType().isInternalMovement()) {
                return zero;
            }
            sign = -txn.getType().primarySign();
        }
        return Money.ofMinor(txn.getAmountMinor() * sign, txn.getCurrency());
    }

    /**
     * The true direction of a transaction with respect to its primary account.
     *
     * <p>Identical to {@link TransactionType#primarySign()} except for
     * reversals, where the direction is the opposite of whatever is being
     * reversed. A reversed salary credit is an outflow; a reversed purchase is
     * an inflow. Assuming one or the other would be wrong half the time.
     */
    public static int effectiveDirection(CanonicalTransaction txn,
                                         Function<String, CanonicalTransaction> resolveReversalTarget) {
        if (txn.getType() == TransactionType.REVERSAL && txn.getReversesId() != null
                && resolveReversalTarget != null) {
            CanonicalTransaction original = resolveReversalTarget.apply(txn.getReversesId());
            if (original != null) {
                return -original.getType().primarySign();
            }
        }
        return txn.getType().primarySign();
    }

    /**
     * The effect of a transaction on a credit card's outstanding liability.
     *
     * <p>Positive increases what is owed. A purchase on the card raises the
     * balance; a refund or reversal lowers it; a bill payment, which arrives
     * with the card as the <em>counter</em> account, lowers it.
     *
     * <p>This is the single rule that keeps a credit card from being
     * double-counted. The purchase is the expense. The payment is the
     * settlement of this liability, and settling a liability is not spending.
     */
    public static Money cardLiabilityEffect(CanonicalTransaction txn, String cardAccountId) {
        Money zero = Money.zero(txn.getCurrency());
        if (!txn.countsTowardTotals() || cardAccountId == null) {
            return zero;
        }

        if (cardAccountId.equals(txn.getAccountId())) {
            int sign = switch (txn.getType()) {
                case EXPENSE -> +1;
                case REFUND, REVERSAL -> -1;
                // Money leaving the card itself (a cash advance modelled as a
                // transfer) increases the drawn balance.
                case TRANSFER -> +1;
                case INCOME, CARD_PAYMENT -> 0;
            };
            return Money.ofMinor(txn.getAmountMinor() * sign, txn.getCurrency());
        }

        if (cardAccountId.equals(txn.getCounterAccountId()) && txn.getType() == TransactionType.CARD_PAYMENT) {
            return Money.ofMinor(-txn.getAmountMinor(), txn.getCurrency());
        }

        return zero;
    }

    /**
     * Whether a transaction contributes to reported spending.
     *
     * <p>Both conditions are required, and each catches something the other
     * misses. The type check excludes transfers and bill payments. The category
     * check excludes an {@link TransactionType#EXPENSE} that belongs to a
     * non-spending category anyway — an ATM withdrawal or a mutual fund
     * purchase, which a parser will usually see as a debit.
     *
     * @param categoryExcluded whether the transaction's category is marked
     *     {@code excludedFromSpending}
     */
    public static boolean countsAsSpending(CanonicalTransaction txn, boolean categoryExcluded) {
        return txn.countsTowardTotals()
                && txn.getType().addsToSpending()
                && !categoryExcluded;
    }

    /** Whether a transaction reduces reported spending (refund or reversal). */
    public static boolean reducesSpending(CanonicalTransaction txn, boolean categoryExcluded) {
        return txn.countsTowardTotals()
                && txn.getType().subtractsFromSpending()
                && !categoryExcluded;
    }

    /** Whether a transaction contributes to reported income. */
    public static boolean countsAsIncome(CanonicalTransaction txn) {
        return txn.countsTowardTotals() && txn.getType().addsToIncome();
    }

    /**
     * Net spending: gross expenses less refunds and reversals.
     *
     * <p>Both arguments are positive magnitudes. The result can legitimately be
     * negative — a month whose only activity was a large refund really did have
     * negative net spending, and clamping that to zero would hide the refund.
     */
    public static Money netSpending(Money grossSpending, Money offsets) {
        return grossSpending.minus(offsets);
    }

    /** Net cash flow: income less net spending. */
    public static Money netCashFlow(Money income, Money netSpending) {
        return income.minus(netSpending);
    }

    /**
     * Available credit on a card, or null when the limit is not configured.
     *
     * <p>Returns null rather than zero for an unconfigured limit: zero would
     * render as "no credit available", which is a factual claim the application
     * has no basis for.
     */
    public static Money availableCredit(Account card, Money outstanding) {
        Money limit = card.creditLimit();
        if (limit == null) {
            return null;
        }
        return limit.minus(outstanding);
    }

    /**
     * Percentage of a budget consumed, rounded down, using integer arithmetic.
     * Returns 0 for a non-positive budget rather than dividing by zero.
     */
    public static int budgetUsedPercent(Money spent, Money budget) {
        if (budget == null || budget.minor() <= 0) {
            return 0;
        }
        long percent = Math.multiplyExact(spent.minor(), 100L) / budget.minor();
        // Clamp into int range; a budget overshoot of >2 billion percent is not
        // worth modelling, but an overflow would be a wrong number.
        return (int) Math.clamp(percent, 0, Integer.MAX_VALUE);
    }
}
