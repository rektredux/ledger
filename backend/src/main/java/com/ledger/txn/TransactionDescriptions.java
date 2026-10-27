package com.ledger.txn;

import java.time.format.DateTimeFormatter;

/**
 * Builds the short human-readable description shown in listings.
 *
 * <p>Separate from the parsers because the description is derived from the
 * stored transaction, not from the message, and must stay consistent however
 * the transaction arrived. Contains nothing sensitive: a type, a merchant name
 * and a date.
 */
public final class TransactionDescriptions {

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private TransactionDescriptions() {
    }

    public static String describe(CanonicalTransaction txn) {
        StringBuilder sb = new StringBuilder(label(txn.getType()));
        if (txn.getMerchant() != null && !txn.getMerchant().isBlank()) {
            sb.append(" · ").append(txn.getMerchant());
        } else if (txn.getMerchantRaw() != null && !txn.getMerchantRaw().isBlank()) {
            sb.append(" · ").append(txn.getMerchantRaw());
        }
        if (txn.getOccurredOn() != null) {
            sb.append(" · ").append(SHORT_DATE.format(txn.getOccurredOn()));
        }
        if (txn.getStatus() == TransactionStatus.PENDING) {
            sb.append(" (pending)");
        }
        return sb.toString();
    }

    public static String label(TransactionType type) {
        if (type == null) {
            return "Transaction";
        }
        return switch (type) {
            case EXPENSE -> "Payment";
            case INCOME -> "Credit";
            case REFUND -> "Refund";
            case REVERSAL -> "Reversal";
            case TRANSFER -> "Transfer";
            case CARD_PAYMENT -> "Card bill payment";
        };
    }
}
