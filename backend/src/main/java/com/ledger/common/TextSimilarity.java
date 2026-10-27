package com.ledger.common;

import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/**
 * String similarity for merchant comparison during deduplication.
 *
 * <p>Deliberately simple and local: a token-overlap (Jaccard) measure combined
 * with a normalised edit distance. It is not trying to be a general fuzzy
 * matcher. The inputs are already normalised merchant names, so the realistic
 * differences are small — {@code "Swiggy"} vs {@code "Swiggy Instamart"},
 * {@code "Uber"} vs {@code "Uber India"} — and the measure only has to be good
 * enough to separate "almost certainly the same shop" from "unrelated".
 *
 * <p>The matcher never relies on this alone: merchant similarity is one
 * weighted signal among several, and a high merchant score cannot by itself
 * merge two transactions.
 */
public final class TextSimilarity {

    private TextSimilarity() {
    }

    /**
     * Similarity in [0,1]. Returns 1.0 for equal strings (ignoring case and
     * surrounding whitespace) and 0.0 when either side is absent.
     *
     * <p>The result is the greater of token overlap and character similarity.
     * Taking the maximum rather than the mean because the two measures fail on
     * different inputs: token overlap handles added or reordered words
     * ({@code "Cafe Coffee Day"} / {@code "Coffee Day Cafe"}) but scores
     * nothing for a typo inside a single word, while edit distance handles the
     * typo but is punished heavily by an added word.
     */
    public static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        String left = a.trim().toLowerCase(Locale.ROOT);
        String right = b.trim().toLowerCase(Locale.ROOT);
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        if (left.equals(right)) {
            return 1.0;
        }
        // A containment relationship is the common real case: the same merchant
        // reported with and without a sub-brand or city suffix.
        if (left.contains(right) || right.contains(left)) {
            int shorter = Math.min(left.length(), right.length());
            int longer = Math.max(left.length(), right.length());
            // Scaled so a long extra suffix still scores well but not perfectly.
            return 0.80 + 0.20 * ((double) shorter / longer);
        }
        return Math.max(tokenOverlap(left, right), characterSimilarity(left, right));
    }

    /** Jaccard index over whitespace-separated tokens. */
    static double tokenOverlap(String left, String right) {
        Set<String> leftTokens = tokenize(left);
        Set<String> rightTokens = tokenize(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String value) {
        Set<String> tokens = new HashSet<>();
        for (String token : value.split("[^a-z0-9]+")) {
            // One-character tokens are noise in merchant names.
            if (token.length() > 1) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** {@code 1 - (editDistance / maxLength)}, floored at 0. */
    static double characterSimilarity(String left, String right) {
        int distance = levenshtein(left, right);
        int longest = Math.max(left.length(), right.length());
        if (longest == 0) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 - ((double) distance / longest));
    }

    /**
     * Levenshtein edit distance, using two rolling rows rather than a full
     * matrix. Inputs are merchant names (tens of characters), so this is
     * cheap, but ingestion scores many candidates per record and the full
     * matrix would be needless allocation.
     */
    static int levenshtein(String a, String b) {
        int n = b.length();
        int[] previous = new int[n + 1];
        int[] current = new int[n + 1];

        for (int j = 0; j <= n; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int substitution = previous[j - 1] + (ca == b.charAt(j - 1) ? 0 : 1);
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                current[j] = Math.min(substitution, Math.min(deletion, insertion));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[n];
    }
}
