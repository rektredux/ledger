package com.ledger.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Redaction and hashing helpers used before any provider content is persisted
 * or logged.
 *
 * <p>The application's privacy position is that it holds the user's financial
 * ledger, not their mailbox. Email bodies are parsed in memory and then
 * discarded: what survives is the extracted transaction fields plus a short
 * redacted excerpt kept so the user can see *why* a record parsed the way it
 * did. That excerpt still passes through {@link #redact} first, because a bank
 * alert body routinely contains the full account number, an OTP, or a card
 * number alongside the amount.
 *
 * <p>This is defence in depth, not a guarantee: these are patterns, and a bank
 * can always invent a new format. It is the reason excerpts are capped short
 * and full bodies are never written at all.
 */
public final class Redaction {

    private Redaction() {
    }

    /** Maximum length of a stored excerpt. */
    public static final int EXCERPT_LIMIT = 240;

    // A card-like run of 13-19 digits, optionally grouped by spaces or hyphens.
    private static final Pattern CARD_NUMBER =
            Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    // Any remaining run of 6+ digits: account numbers, OTPs, reference numbers
    // long enough to be identifying. Amounts are shorter than this in practice
    // once decimal separators are considered, and the amount is extracted into
    // its own column before redaction runs anyway.
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{6,}\\b");

    // "xxxx1234" / "XX1234" masked fragments banks already use: keep the last 4
    // because that is how an account is attributed, drop the rest.
    private static final Pattern MASKED_TAIL =
            Pattern.compile("(?i)\\b[x*]{2,}\\s?(\\d{3,4})\\b");

    private static final Pattern OTP_CONTEXT =
            Pattern.compile("(?i)\\b(otp|one[\\s-]?time\\s?password|pin|cvv)\\b[^\\d]{0,20}\\d{3,8}");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Masks identifiers that must never be persisted, then collapses
     * whitespace. Does not truncate; see {@link #excerpt}.
     */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = input;
        // Order matters: OTP/PIN context first (it is the most sensitive and its
        // digit runs are short enough to survive the later patterns), then card
        // numbers, then masked tails, then any other long digit run.
        out = OTP_CONTEXT.matcher(out).replaceAll("[redacted]");
        out = CARD_NUMBER.matcher(out).replaceAll("[card]");
        out = MASKED_TAIL.matcher(out).replaceAll("x$1");
        out = LONG_DIGITS.matcher(out).replaceAll("[redacted]");
        out = WHITESPACE.matcher(out).replaceAll(" ").trim();
        return out;
    }

    /** Redacts and truncates to {@link #EXCERPT_LIMIT} characters. */
    public static String excerpt(String input) {
        String redacted = redact(input);
        if (redacted == null) {
            return null;
        }
        return redacted.length() <= EXCERPT_LIMIT
                ? redacted
                : redacted.substring(0, EXCERPT_LIMIT - 1) + "…";
    }

    /**
     * SHA-256 of the original content, hex encoded.
     *
     * <p>Lets the ingestion pipeline recognise that it has seen byte-identical
     * content before (the same alert delivered twice, a forwarded copy) without
     * retaining the content itself.
     */
    public static String contentHash(String content) {
        if (content == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JRE.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Masks an email address for log output: {@code someone@example.com}
     * becomes {@code s*****e@example.com}. The domain is kept because knowing
     * the sender's bank is useful when diagnosing a parser, and it is not
     * personally identifying on its own.
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email == null ? null : "[address]";
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return "*".repeat(local.length()) + domain;
        }
        return local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1) + domain;
    }
}
