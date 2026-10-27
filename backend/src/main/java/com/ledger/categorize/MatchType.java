package com.ledger.categorize;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** How a merchant alias or category rule pattern is compared against a value. */
public enum MatchType {

    /** The whole normalized value equals the pattern. */
    EXACT,

    /** The normalized value contains the pattern as a substring. */
    CONTAINS,

    /**
     * The pattern is a Java regular expression.
     *
     * <p>Patterns are user-authorable, so they are compiled defensively:
     * an invalid expression disables its own rule rather than breaking
     * categorization for every transaction. There is no timeout on matching, so
     * a catastrophically backtracking pattern can be slow; rules are applied to
     * short merchant strings only, which bounds the damage.
     */
    REGEX;

    /**
     * Tests {@code value} against {@code pattern}. Both are compared
     * lower-cased. Returns false for a null value or an uncompilable regex.
     */
    public boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        String v = value.toLowerCase(Locale.ROOT);
        String p = pattern.toLowerCase(Locale.ROOT);
        return switch (this) {
            case EXACT -> v.equals(p);
            case CONTAINS -> v.contains(p);
            case REGEX -> regexMatches(p, v);
        };
    }

    private static boolean regexMatches(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * Specificity, used to break ties between rules of equal priority: a longer
     * and more constrained pattern should win over a vague one.
     */
    public int specificityBonus() {
        return switch (this) {
            case EXACT -> 20;
            case REGEX -> 10;
            case CONTAINS -> 0;
        };
    }

    public static MatchType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CONTAINS;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "EXACT" -> EXACT;
            case "REGEX" -> REGEX;
            default -> CONTAINS;
        };
    }
}
