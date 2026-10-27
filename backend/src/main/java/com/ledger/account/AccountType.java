package com.ledger.account;

/**
 * The kind of account or payment instrument.
 *
 * <p>Note that UPI is deliberately not a type here. A UPI payment is a rail,
 * not an account: the money comes out of a bank account the user already holds.
 * Modelling UPI as its own account would make a single payment look like a
 * transfer to "UPI" followed by a spend, and would double-count it. UPI appears
 * instead as an {@code instrumentHint} on a {@link #BANK} account (the VPA
 * fragment), which is how parsed alerts are attributed.
 */
public enum AccountType {

    /** Physical cash. Only ever populated by hand; no provider can observe it. */
    CASH,

    /** A savings or current account, including its debit card and UPI handles. */
    BANK,

    /**
     * A credit card. Balance semantics are inverted relative to the others: a
     * purchase increases what is owed rather than decreasing an asset, and the
     * tracked figure is an outstanding liability.
     */
    CREDIT_CARD,

    /** A prepaid wallet or similar stored-value instrument. */
    PREPAID_WALLET;

    /** True when the balance represents money owed rather than money held. */
    public boolean isLiability() {
        return this == CREDIT_CARD;
    }

    /** True when statement cycle and due date configuration applies. */
    public boolean hasBillingCycle() {
        return this == CREDIT_CARD;
    }

    /**
     * True when no automated provider can observe movement on this account, so
     * its balance is only ever as good as what the user enters. Surfaced as a
     * coverage warning rather than presented as a complete balance.
     */
    public boolean isManualOnly() {
        return this == CASH;
    }

    public static AccountType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("account type is required");
        }
        String v = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return switch (v) {
            case "CASH" -> CASH;
            case "BANK", "SAVINGS", "CURRENT", "DEBIT", "DEBIT_CARD" -> BANK;
            case "CREDIT_CARD", "CREDITCARD", "CARD", "CC" -> CREDIT_CARD;
            case "PREPAID_WALLET", "WALLET", "PREPAID" -> PREPAID_WALLET;
            default -> throw new IllegalArgumentException("unrecognised account type: " + raw);
        };
    }
}
