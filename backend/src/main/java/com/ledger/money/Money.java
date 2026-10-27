package com.ledger.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact monetary amount, held as an integer count of minor units.
 *
 * <p>There is deliberately no {@code double} or {@code float} anywhere in this
 * class or in the persistence layer. {@code 0.1 + 0.2 != 0.3} in binary
 * floating point, and a ledger that accumulates thousands of such amounts will
 * drift away from the user's real statements in a way that is very hard to
 * debug after the fact. Every amount in this application is a {@code long}
 * count of paise (for INR) and all arithmetic is integer arithmetic.
 *
 * <p>Amounts here are signed, because intermediate report arithmetic genuinely
 * needs negatives (a net cash flow can be negative, a refund offsets an
 * expense). Persisted transaction amounts are a separate matter: those are
 * stored as positive magnitudes with direction derived from the transaction
 * type. See {@code LedgerMath}.
 *
 * <p>{@code Money} is a value type: operations return new instances and never
 * mutate the receiver.
 */
public record Money(long minor, String currency) implements Comparable<Money> {

    /** The application's default currency. */
    public static final String INR = "INR";

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO-4217 code: " + currency);
        }
        currency = currency.toUpperCase();
    }

    public static Money ofMinor(long minor, String currency) {
        return new Money(minor, currency);
    }

    /** Zero in the given currency. */
    public static Money zero(String currency) {
        return new Money(0L, currency);
    }

    public static Money zeroInr() {
        return new Money(0L, INR);
    }

    /**
     * Builds an amount from a decimal major-unit value, e.g. {@code 1234.50}
     * INR becomes {@code 123450} paise.
     *
     * @throws IllegalArgumentException if the value has more decimal places than
     *     the currency supports. Silently rounding a user's input would make the
     *     ledger disagree with their bank, so this is rejected rather than
     *     coerced. Use {@link #fromMajorRounded} when a source genuinely
     *     supplies extra precision that must be accepted.
     */
    public static Money fromMajor(BigDecimal major, String currency) {
        Objects.requireNonNull(major, "major");
        int scale = fractionDigits(currency);
        try {
            return new Money(major.setScale(scale).movePointRight(scale).longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "amount " + major.toPlainString() + " cannot be represented exactly in " + currency, e);
        }
    }

    /** As {@link #fromMajor} but rounds half-up to the currency's precision. */
    public static Money fromMajorRounded(BigDecimal major, String currency) {
        Objects.requireNonNull(major, "major");
        int scale = fractionDigits(currency);
        return new Money(
                major.setScale(scale, RoundingMode.HALF_UP).movePointRight(scale).longValueExact(), currency);
    }

    /** Parses a decimal string such as {@code "1,234.50"} or {@code "1234.5"}. */
    public static Money parse(String text, String currency) {
        Objects.requireNonNull(text, "text");
        String cleaned = text.trim()
                .replace(",", "")
                .replace(" ", "")   // non-breaking space, common in HTML emails
                .replace(" ", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("empty amount");
        }
        return fromMajorRounded(new BigDecimal(cleaned), currency);
    }

    /** Number of decimal places the currency uses (2 for INR, 0 for JPY). */
    public static int fractionDigits(String currency) {
        try {
            int digits = Currency.getInstance(currency.toUpperCase()).getDefaultFractionDigits();
            // -1 is returned for pseudo-currencies such as XXX.
            return digits < 0 ? 2 : digits;
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    /** The amount as an exact decimal in major units. Never lossy. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, fractionDigits(currency));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(minor), currency);
    }

    public Money abs() {
        return minor < 0 ? negated() : this;
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(minor, factor), currency);
    }

    /**
     * Scales by a percentage using integer arithmetic, rounding half-up.
     * Used for budget thresholds, never for currency conversion.
     */
    public Money percentage(int percent) {
        long scaled = Math.multiplyExact(minor, (long) percent);
        return new Money(Math.floorDiv(scaled + (scaled >= 0 ? 50 : -50), 100), currency);
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public boolean isPositive() {
        return minor > 0;
    }

    /** True when the two amounts differ by no more than {@code toleranceMinor}. */
    public boolean isWithin(Money other, long toleranceMinor) {
        requireSameCurrency(other);
        return Math.abs(Math.subtractExact(minor, other.minor)) <= toleranceMinor;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            // Cross-currency arithmetic needs an exchange rate and a rate date.
            // The application has no rate source, so rather than invent one we
            // refuse: a wrong total is worse than a visible error.
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return currency + " " + toMajor().toPlainString();
    }

    /** Thrown when amounts in different currencies are combined. */
    public static class CurrencyMismatchException extends RuntimeException {
        public CurrencyMismatchException(String a, String b) {
            super("cannot combine amounts in " + a + " and " + b + " without an exchange rate");
        }
    }
}
