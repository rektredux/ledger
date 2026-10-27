package com.ledger.txn;

/**
 * The kind of financial event a canonical transaction represents.
 *
 * <p>This enum is where the application's accounting rules live. Stored amounts
 * are always positive magnitudes; direction and reporting treatment are derived
 * from the type. Keeping it that way means "is this an expense?" has exactly
 * one answer in exactly one place, rather than depending on whether some
 * upstream parser happened to write a negative number.
 *
 * <p>The distinctions that matter most, and the mistakes they prevent:
 *
 * <ul>
 *   <li>A credit card <em>purchase</em> is an {@link #EXPENSE}; paying the card
 *       bill is a {@link #CARD_PAYMENT}. If the bill payment were also an
 *       expense, every rupee spent on the card would be counted twice.</li>
 *   <li>Moving money between two accounts the user owns is a {@link #TRANSFER}
 *       and is neither spending nor income. Counting it would inflate both
 *       sides of the cash flow statement.</li>
 *   <li>A {@link #REFUND} offsets the original purchase rather than appearing as
 *       income, so category totals stay honest.</li>
 *   <li>A {@link #REVERSAL} means the original event did not really happen (a
 *       failed or rolled-back debit), which is subtly different from a refund of
 *       a completed purchase.</li>
 * </ul>
 */
public enum TransactionType {

    /** Money spent. The only type that adds to "total spending". */
    EXPENSE(Direction.OUTFLOW, false),

    /** Money received that genuinely increases net worth: salary, interest. */
    INCOME(Direction.INFLOW, false),

    /**
     * Money returned for a purchase that did happen. Offsets the original
     * expense (and its category) instead of counting as income. Links to the
     * original purchase via {@code refundOfId} when it can be identified.
     */
    REFUND(Direction.INFLOW, false),

    /**
     * A prior transaction that did not actually complete and was rolled back by
     * the bank. Links to the original via {@code reversesId}; the net effect is
     * to cancel the original out entirely.
     *
     * <p>Direction here is the declared default (a reversed debit, by far the
     * common case in bank alerts). When {@code reversesId} is set, the true
     * direction is the opposite of the referenced transaction's and is resolved
     * by {@code LedgerMath}, not by this constant.
     */
    REVERSAL(Direction.INFLOW, false),

    /**
     * Money moved between two accounts the user owns. Must name a counter
     * account. Excluded from spending and income.
     */
    TRANSFER(Direction.OUTFLOW, true),

    /**
     * A credit card bill payment: a liability settlement, not consumption.
     * Money leaves the funding account ({@code accountId}) and reduces the
     * outstanding balance of the card ({@code counterAccountId}). Excluded from
     * spending and income.
     */
    CARD_PAYMENT(Direction.OUTFLOW, true);

    /** Which way money moves with respect to the primary account. */
    public enum Direction {
        /** Money leaves the primary account. */
        OUTFLOW(-1),
        /** Money arrives in the primary account. */
        INFLOW(+1);

        private final int sign;

        Direction(int sign) {
            this.sign = sign;
        }

        /** {@code -1} for an outflow, {@code +1} for an inflow. */
        public int sign() {
            return sign;
        }
    }

    private final Direction direction;
    private final boolean requiresCounterAccount;

    TransactionType(Direction direction, boolean requiresCounterAccount) {
        this.direction = direction;
        this.requiresCounterAccount = requiresCounterAccount;
    }

    public Direction direction() {
        return direction;
    }

    /** Sign of this type's effect on the primary account's balance. */
    public int primarySign() {
        return direction.sign();
    }

    /**
     * True when the type is meaningless without a second account, and the
     * schema enforces that {@code counter_account_id} is set.
     */
    public boolean requiresCounterAccount() {
        return requiresCounterAccount;
    }

    /** True when this type adds to reported spending. Only {@link #EXPENSE}. */
    public boolean addsToSpending() {
        return this == EXPENSE;
    }

    /**
     * True when this type subtracts from reported spending: a refund or a
     * reversal gives money back for something previously counted as spent.
     */
    public boolean subtractsFromSpending() {
        return this == REFUND || this == REVERSAL;
    }

    /** True when this type adds to reported income. Only {@link #INCOME}. */
    public boolean addsToIncome() {
        return this == INCOME;
    }

    /**
     * True when the transaction moves money without changing net worth, so it
     * must be excluded from both spending and income totals.
     */
    public boolean isInternalMovement() {
        return this == TRANSFER || this == CARD_PAYMENT;
    }

    /** True when this type participates in spending totals at all. */
    public boolean affectsSpending() {
        return addsToSpending() || subtractsFromSpending();
    }

    /**
     * Effect on a credit card's outstanding liability, from the perspective of
     * the card account.
     *
     * <p>{@code +1} increases what is owed (a purchase on the card),
     * {@code -1} reduces it (a bill payment, refund or reversal), {@code 0} no
     * effect.
     */
    public int cardLiabilitySign() {
        return switch (this) {
            case EXPENSE -> +1;
            case REFUND, REVERSAL, CARD_PAYMENT -> -1;
            // A cash advance or transfer involving a card is modelled as a
            // TRANSFER; it changes the funding side, not the liability, unless
            // the card is the counter account (handled by the caller).
            case TRANSFER, INCOME -> 0;
        };
    }

    /** Parses leniently, for CSV import and provider payloads. */
    public static TransactionType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("transaction type is required");
        }
        String v = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return switch (v) {
            case "EXPENSE", "DEBIT", "SPEND", "PURCHASE" -> EXPENSE;
            case "INCOME", "CREDIT", "DEPOSIT", "SALARY" -> INCOME;
            case "REFUND", "REFUNDED", "CHARGEBACK" -> REFUND;
            case "REVERSAL", "REVERSED", "FAILED" -> REVERSAL;
            case "TRANSFER", "SELF_TRANSFER" -> TRANSFER;
            case "CARD_PAYMENT", "CARD_BILL", "BILL_PAYMENT", "CREDIT_CARD_PAYMENT" -> CARD_PAYMENT;
            default -> throw new IllegalArgumentException("unrecognised transaction type: " + raw);
        };
    }
}
