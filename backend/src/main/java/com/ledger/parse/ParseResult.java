package com.ledger.parse;

import com.ledger.money.Money;
import com.ledger.txn.TransactionStatus;
import com.ledger.txn.TransactionType;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * What a parser managed to extract from one message.
 *
 * <p>Every field is permitted to be null and null means exactly one thing:
 * <em>the message did not state this</em>. Nothing here is defaulted, inferred
 * or filled in with a plausible value. That restraint is deliberate — a parser
 * that guesses a date when the alert omitted one produces a transaction that
 * looks like a fact and silently lands in the wrong month.
 *
 * <p>Inference happens later and separately: the categorizer assigns a category
 * with a confidence, the account resolver attributes an instrument, and the
 * matcher decides about duplication. Each records what it did, so the UI can
 * always distinguish what a bank said from what the application concluded.
 *
 * @param amount         the transaction amount, as an exact magnitude
 * @param occurredOn     the transaction date as stated by the source
 * @param occurredAtTime time of day, when stated
 * @param merchantRaw    the merchant/payee string verbatim, before normalisation
 * @param type           direction and nature of the transaction
 * @param status         pending vs posted, when the source distinguishes
 * @param instrumentHint card/account tail or UPI fragment naming the instrument
 * @param reference      the provider's identifier for the event (UPI ref, txn id)
 * @param description    a short human-readable summary derived from the message
 * @param confidence     0 to 1: how sure the parser is that it read this
 *     correctly. Drives whether the record is trusted or sent to review.
 */
public record ParseResult(Money amount,
                          LocalDate occurredOn,
                          LocalTime occurredAtTime,
                          String merchantRaw,
                          TransactionType type,
                          TransactionStatus status,
                          String instrumentHint,
                          String reference,
                          String description,
                          double confidence) {

    /**
     * True when enough was extracted to create a ledger entry: an amount, a
     * date and a direction. Anything less belongs in the exception queue,
     * because a transaction missing any of the three cannot be counted.
     */
    public boolean isComplete() {
        return amount != null && occurredOn != null && type != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; parsers fill in whatever they find. */
    public static final class Builder {
        private Money amount;
        private LocalDate occurredOn;
        private LocalTime occurredAtTime;
        private String merchantRaw;
        private TransactionType type;
        private TransactionStatus status = TransactionStatus.POSTED;
        private String instrumentHint;
        private String reference;
        private String description;
        private double confidence = 0.5;

        public Builder amount(Money value) {
            this.amount = value;
            return this;
        }

        public Builder occurredOn(LocalDate value) {
            this.occurredOn = value;
            return this;
        }

        public Builder occurredAtTime(LocalTime value) {
            this.occurredAtTime = value;
            return this;
        }

        public Builder merchantRaw(String value) {
            this.merchantRaw = blankToNull(value);
            return this;
        }

        public Builder type(TransactionType value) {
            this.type = value;
            return this;
        }

        public Builder status(TransactionStatus value) {
            this.status = value;
            return this;
        }

        public Builder instrumentHint(String value) {
            this.instrumentHint = blankToNull(value);
            return this;
        }

        public Builder reference(String value) {
            this.reference = blankToNull(value);
            return this;
        }

        public Builder description(String value) {
            this.description = blankToNull(value);
            return this;
        }

        public Builder confidence(double value) {
            this.confidence = Math.clamp(value, 0.0, 1.0);
            return this;
        }

        public ParseResult build() {
            return new ParseResult(amount, occurredOn, occurredAtTime, merchantRaw, type, status,
                    instrumentHint, reference, description, confidence);
        }

        private static String blankToNull(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }
}
