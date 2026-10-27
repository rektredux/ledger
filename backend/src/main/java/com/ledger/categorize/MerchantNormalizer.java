package com.ledger.categorize;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a payment rail's merchant string into a stable, readable name.
 *
 * <p>Bank and card alerts do not contain merchant names so much as routing
 * debris with a name somewhere inside. The same shop arrives as:
 *
 * <pre>
 *   UPI/STARBUCKS COFFEE/429812345678/Payment from ph
 *   POS 4567 STARBUCKS IN BLR
 *   STARBUCK*INDIA        BENGALURU IN
 * </pre>
 *
 * <p>Left alone, each variant becomes its own row in merchant-wise spending and
 * needs its own category rule. Normalisation runs in three stages:
 *
 * <ol>
 *   <li><b>Strip</b> rail prefixes, reference numbers and trailing location and
 *       country noise.</li>
 *   <li><b>Alias</b> the remainder against {@link MerchantAlias} rows, most
 *       specific pattern first, collapsing the variants onto one name.</li>
 *   <li><b>Title-case</b> whatever survives, since alerts are usually SHOUTING
 *       and an un-aliased merchant should still be presentable.</li>
 * </ol>
 *
 * <p>The raw string is always preserved on the transaction as
 * {@code merchantRaw}: normalisation is lossy and occasionally wrong, and the
 * original is the only way to diagnose that.
 */
@Service
public class MerchantNormalizer {

    /**
     * Payment rail and channel prefixes. Anchored at the start and applied
     * repeatedly, because strings like {@code "UPI/POS/..."} do occur.
     */
    private static final Pattern RAIL_PREFIX = Pattern.compile(
            "^(?:upi|pos|atm|neft|imps|rtgs|ach|ecom|inf|bil|mmt|tpt|chq|vps|onl|nfs)"
                    + "[\\s/:*#-]+", Pattern.CASE_INSENSITIVE);

    /** A UPI virtual payment address: keep the handle, drop the bank suffix. */
    private static final Pattern VPA = Pattern.compile("([a-z0-9._-]+)@[a-z]+", Pattern.CASE_INSENSITIVE);

    /** Long digit runs: transaction references, not part of any name. */
    private static final Pattern REFERENCE_DIGITS = Pattern.compile("\\b\\d{5,}\\b");

    /** Trailing country / city noise that card networks append. */
    private static final Pattern TRAILING_NOISE = Pattern.compile(
            "[\\s,]+(?:in|ind|india|bengaluru|bangalore|mumbai|delhi|new\\s+delhi|chennai|hyderabad|pune|"
                    + "kolkata|gurgaon|gurugram|noida|blr|bom|del|maa|hyd)\\.?$",
            Pattern.CASE_INSENSITIVE);

    /** Boilerplate that follows the merchant name after a separator. */
    private static final Pattern TRAILING_PHRASE = Pattern.compile(
            "[/|-]\\s*(?:payment\\s+from.*|paid\\s+via.*|ref(?:no)?\\.?\\s*\\S*|txn.*|"
                    + "upi\\s*ref.*|collect.*|autopay.*)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PUNCTUATION_RUN = Pattern.compile("[*_|]+");
    private static final Pattern SEPARATOR_RUN = Pattern.compile("[\\s/:#,.-]{2,}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LEADING_SEPARATORS = Pattern.compile("^[\\s/:#,.-]+");
    private static final Pattern TRAILING_SEPARATORS = Pattern.compile("[\\s/:#,.-]+$");

    /** Words left lowercase when title-casing, unless they lead the name. */
    private static final List<String> LOWERCASE_WORDS = List.of("and", "of", "the", "for", "at", "in", "on");

    private final MerchantAliasRepository aliases;

    /**
     * Snapshot of the alias rows, most specific first. Aliases change only when
     * the user edits them, and normalisation runs once per ingested record, so
     * the whole (small) table is held in memory rather than queried per record.
     */
    private volatile List<MerchantAlias> aliasCache;

    public MerchantNormalizer(MerchantAliasRepository aliases) {
        this.aliases = aliases;
    }

    /** Drops the cached alias snapshot. Call after editing aliases. */
    public void invalidate() {
        this.aliasCache = null;
    }

    private List<MerchantAlias> aliasesBySpecificity() {
        List<MerchantAlias> cached = aliasCache;
        if (cached == null) {
            cached = aliases.findAll().stream()
                    .sorted(Comparator.comparingInt(MerchantAlias::rank).reversed())
                    .toList();
            aliasCache = cached;
        }
        return cached;
    }

    /**
     * The normalised, display-ready merchant name, or null when the input
     * reduces to nothing recognisable.
     *
     * <p>Returning null rather than a placeholder matters: a null merchant is
     * reported honestly as "Unknown" in merchant breakdowns, whereas deriving a
     * name from reference digits would invent a new phantom merchant per
     * transaction.
     */
    public String normalize(String raw) {
        String stripped = strip(raw);
        if (stripped == null) {
            return null;
        }
        for (MerchantAlias alias : aliasesBySpecificity()) {
            if (alias.matches(stripped)) {
                return alias.getCanonicalName();
            }
        }
        return titleCase(stripped);
    }

    /**
     * Stage 1: remove rail debris, returning a lowercased core, or null when
     * nothing meaningful remains.
     */
    String strip(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        value = TRAILING_PHRASE.matcher(value).replaceAll("");

        // Rail prefixes can stack; the bound stops a pathological input from
        // looping and makes the worst case obvious.
        for (int i = 0; i < 4; i++) {
            String next = RAIL_PREFIX.matcher(value).replaceFirst("");
            if (next.equals(value)) {
                break;
            }
            value = next;
        }

        value = VPA.matcher(value).replaceAll("$1");
        value = REFERENCE_DIGITS.matcher(value).replaceAll(" ");
        value = PUNCTUATION_RUN.matcher(value).replaceAll(" ");
        value = SEPARATOR_RUN.matcher(value).replaceAll(" ");
        value = WHITESPACE.matcher(value).replaceAll(" ").trim();
        value = TRAILING_NOISE.matcher(value).replaceAll("").trim();
        value = LEADING_SEPARATORS.matcher(value).replaceAll("");
        value = TRAILING_SEPARATORS.matcher(value).replaceAll("");

        if (value.isEmpty()) {
            return null;
        }
        // A residue that is only digits, or a single character, carries no
        // merchant information and is better reported as unknown.
        String compact = value.replaceAll("[^a-zA-Z0-9]", "");
        if (compact.length() < 2 || compact.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** Stage 3: make an un-aliased merchant presentable. */
    String titleCase(String lowercase) {
        if (lowercase == null || lowercase.isEmpty()) {
            return null;
        }
        String[] words = WHITESPACE.split(lowercase);
        StringBuilder out = new StringBuilder(lowercase.length());
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            if (i > 0 && LOWERCASE_WORDS.contains(word)) {
                out.append(word);
            } else if (word.length() <= 3 && word.chars().allMatch(Character::isLetter)) {
                // Short all-letter tokens in merchant strings are usually
                // acronyms (HP, KFC, IOC) rather than words.
                out.append(word.toUpperCase(Locale.ROOT));
            } else {
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.isEmpty() ? null : out.toString();
    }
}
