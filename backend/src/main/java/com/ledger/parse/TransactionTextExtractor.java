package com.ledger.parse;

import com.ledger.money.Money;
import com.ledger.txn.TransactionType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Field extraction shared by every message parser.
 *
 * <p>Indian bank, card and UPI alerts vary in wording but converge on the same
 * handful of shapes. Keeping the patterns here means a fix to date handling or
 * amount parsing benefits every parser at once, and the patterns can be tested
 * directly against representative strings without a provider or a database.
 *
 * <p>Every method returns {@link Optional}: "not stated" is a normal outcome
 * and is passed up as a null field rather than guessed at.
 */
public final class TransactionTextExtractor {

    private TransactionTextExtractor() {
    }

    // ---------------------------------------------------------------- amount

    /**
     * Amount with the currency marker leading: {@code Rs. 1,234.56},
     * {@code INR 1234}, {@code ₹1,234.56}.
     *
     * <p>The decimal part is optional because plenty of alerts omit it, and the
     * thousands separators are Indian-grouped ({@code 1,23,456.78}), which a
     * naive {@code \d{3}} grouping pattern gets wrong.
     */
    private static final Pattern AMOUNT_PREFIXED = Pattern.compile(
            "(?:(?:rs|inr|₹)\\.?\\s*)([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    /** Amount with the currency marker trailing: {@code 1,234.56 INR}. */
    private static final Pattern AMOUNT_SUFFIXED = Pattern.compile(
            "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:rs|inr|₹)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Phrases that introduce an amount which is NOT the transaction amount.
     * Checked around a candidate match to avoid reading the available balance
     * as the spend, which is the single most common way these alerts are
     * misparsed.
     */
    private static final Pattern NON_TRANSACTION_AMOUNT_CONTEXT = Pattern.compile(
            "(?:available|avbl|avl|closing|opening|current"
                    + "|(?:total|min(?:imum)?)\\.?\\s*(?:amount\\s+)?(?:due|outstanding|payable)"
                    + "|amount\\s+due|outstanding"
                    + "|credit\\s+limit|avl\\.?\\s*lmt|limit"
                    + "|balance|bal\\.?"
                    + "|reward|points|cashback\\s+earned|emi\\s+of)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Extracts the transaction amount.
     *
     * <p>Candidates are examined in order and the first one whose preceding
     * context does not look like a balance, limit or amount-due is returned. If
     * every candidate looks like one of those, empty is returned rather than
     * falling back to the first — reporting a statement balance as a spend is
     * worse than reporting nothing and letting the message surface in the
     * exception queue.
     */
    public static Optional<Money> findAmount(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Optional<Money> prefixed = findAmountWith(text, AMOUNT_PREFIXED);
        if (prefixed.isPresent()) {
            return prefixed;
        }
        return findAmountWith(text, AMOUNT_SUFFIXED);
    }

    private static Optional<Money> findAmountWith(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        // Context for each candidate starts where the previous candidate ended,
        // so one amount's qualifier cannot leak onto the next one. Without this,
        // "Available balance Rs. 45,000. Rs. 250 debited" has the word
        // "balance" inside the look-back window of *both* amounts, and the real
        // transaction amount gets rejected along with the balance.
        int previousMatchEnd = 0;
        while (matcher.find()) {
            if (looksLikeBalanceContext(text, previousMatchEnd, matcher.start())) {
                previousMatchEnd = matcher.end();
                continue;
            }
            Optional<Money> parsed = parseAmount(matcher.group(1));
            if (parsed.isPresent()) {
                return parsed;
            }
            previousMatchEnd = matcher.end();
        }
        return Optional.empty();
    }

    /** Maximum characters of preceding text considered as a qualifier. */
    private static final int CONTEXT_LOOKBACK = 40;

    /**
     * True when the text immediately before a match qualifies it as a balance,
     * limit or amount due rather than a transaction amount.
     */
    private static boolean looksLikeBalanceContext(String text, int previousMatchEnd, int matchStart) {
        int from = Math.max(Math.max(0, matchStart - CONTEXT_LOOKBACK), previousMatchEnd);
        if (from >= matchStart) {
            return false;
        }
        String before = text.substring(from, matchStart);
        return NON_TRANSACTION_AMOUNT_CONTEXT.matcher(before).find();
    }

    /** Parses a bare numeric amount string into exact paise. */
    public static Optional<Money> parseAmount(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            Money money = Money.parse(raw, Money.INR);
            // A zero-amount alert is not a transaction worth recording.
            return money.isPositive() ? Optional.of(money) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ date

    /** Formats tried for an unambiguous ISO date. */
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

    /** {@code 15-03-2026}, {@code 15/03/26}, {@code 15.03.2026} — day first. */
    private static final Pattern NUMERIC_DATE =
            Pattern.compile("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b");

    /** {@code 15 Mar 2026}, {@code 15-Mar-26}, {@code 15 March 2026}. */
    private static final Pattern DAY_MONTH_NAME = Pattern.compile(
            "\\b(\\d{1,2})[\\s-]*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
                    + "[\\s-,]*(\\d{2,4})?\\b",
            Pattern.CASE_INSENSITIVE);

    /** {@code Mar 15, 2026}, {@code March 15 2026}. */
    private static final Pattern MONTH_NAME_DAY = Pattern.compile(
            "\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
                    + "[\\s-]*(\\d{1,2})(?:st|nd|rd|th)?[\\s-,]*(\\d{2,4})?\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> MONTHS =
            List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    /**
     * Extracts the transaction date.
     *
     * <p>Numeric dates are read day-first, which is the Indian convention and
     * what every Indian bank emits. {@code 03/04/2026} is therefore 3 April,
     * not 4 March. Where a pattern yields an impossible date (month &gt; 12)
     * the components are swapped, which rescues the occasional US-formatted
     * merchant receipt.
     *
     * @param fallbackYear year to assume when the text omits one, normally the
     *     year the message was received. Supplied explicitly rather than
     *     defaulting to "now" so that a historical import does not stamp
     *     everything with the current year.
     */
    public static Optional<LocalDate> findDate(String text, int fallbackYear) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        Matcher iso = ISO_DATE.matcher(text);
        if (iso.find()) {
            Optional<LocalDate> date = buildDate(
                    Integer.parseInt(iso.group(1)),
                    Integer.parseInt(iso.group(2)),
                    Integer.parseInt(iso.group(3)));
            if (date.isPresent()) {
                return date;
            }
        }

        Matcher dayMonth = DAY_MONTH_NAME.matcher(text);
        if (dayMonth.find()) {
            int day = Integer.parseInt(dayMonth.group(1));
            int month = MONTHS.indexOf(dayMonth.group(2).toLowerCase(Locale.ROOT)) + 1;
            int year = parseYear(dayMonth.group(3), fallbackYear);
            Optional<LocalDate> date = buildDate(year, month, day);
            if (date.isPresent()) {
                return date;
            }
        }

        Matcher monthDay = MONTH_NAME_DAY.matcher(text);
        if (monthDay.find()) {
            int month = MONTHS.indexOf(monthDay.group(1).toLowerCase(Locale.ROOT)) + 1;
            int day = Integer.parseInt(monthDay.group(2));
            int year = parseYear(monthDay.group(3), fallbackYear);
            Optional<LocalDate> date = buildDate(year, month, day);
            if (date.isPresent()) {
                return date;
            }
        }

        Matcher numeric = NUMERIC_DATE.matcher(text);
        while (numeric.find()) {
            int first = Integer.parseInt(numeric.group(1));
            int second = Integer.parseInt(numeric.group(2));
            int year = parseYear(numeric.group(3), fallbackYear);

            // Day-first by convention; swap only when that is impossible.
            Optional<LocalDate> dayFirst = buildDate(year, second, first);
            if (dayFirst.isPresent()) {
                return dayFirst;
            }
            Optional<LocalDate> monthFirst = buildDate(year, first, second);
            if (monthFirst.isPresent()) {
                return monthFirst;
            }
        }

        return Optional.empty();
    }

    /** Expands a two-digit year into the current century. */
    private static int parseYear(String raw, int fallbackYear) {
        if (raw == null || raw.isBlank()) {
            return fallbackYear;
        }
        int value = Integer.parseInt(raw);
        if (value >= 100) {
            return value;
        }
        int century = (fallbackYear / 100) * 100;
        int candidate = century + value;
        // A two-digit year more than a year in the future is far more likely to
        // belong to the previous century than to be a post-dated transaction.
        return candidate > fallbackYear + 1 ? candidate - 100 : candidate;
    }

    private static Optional<LocalDate> buildDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return Optional.empty();
        }
        // Reject dates far outside any plausible transaction range; these are
        // almost always a misread reference number.
        int thisYear = Year.now().getValue();
        if (year < 2000 || year > thisYear + 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (java.time.DateTimeException e) {
            // Covers an invalid day for the month, e.g. 31 February.
            return Optional.empty();
        }
    }

    private static final Pattern TIME_OF_DAY = Pattern.compile(
            "\\b(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(am|pm|hrs)?\\b", Pattern.CASE_INSENSITIVE);

    /** Extracts a time of day, when stated. */
    public static Optional<LocalTime> findTime(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = TIME_OF_DAY.matcher(text);
        while (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = Integer.parseInt(matcher.group(2));
            int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            String meridiem = matcher.group(4);

            if (meridiem != null && meridiem.equalsIgnoreCase("pm") && hour < 12) {
                hour += 12;
            } else if (meridiem != null && meridiem.equalsIgnoreCase("am") && hour == 12) {
                hour = 0;
            }
            if (hour > 23 || minute > 59 || second > 59) {
                continue;
            }
            return Optional.of(LocalTime.of(hour, minute, second));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------ instrument

    /**
     * Card or account tail. Matches the common phrasings: {@code card ending
     * 4567}, {@code A/c XX1234}, {@code card no. xxxx4567}, {@code ending with
     * 4567}.
     */
    private static final Pattern INSTRUMENT_TAIL = Pattern.compile(
            "(?:(?:card|a/?c(?:count)?|acct)\\s*(?:no\\.?|number|ending(?:\\s+(?:with|in))?|xx+)?"
                    + "\\s*[:#]?\\s*)(?:[x*]{2,}\\s*)?(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    /** A masked tail on its own: {@code XXXX4567}, {@code **4567}. */
    private static final Pattern MASKED_TAIL =
            Pattern.compile("\\b[x*]{3,}\\s?(\\d{4})\\b", Pattern.CASE_INSENSITIVE);

    /** A UPI virtual payment address. */
    private static final Pattern VPA_PATTERN =
            Pattern.compile("\\b([a-z0-9][a-z0-9._-]{2,})@([a-z]{2,})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Extracts the fragment identifying the instrument used.
     *
     * <p>Only ever a fragment — a four digit tail or a VPA. The application has
     * no use for a full card or account number and deliberately does not
     * capture one.
     */
    public static Optional<String> findInstrumentHint(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher explicit = INSTRUMENT_TAIL.matcher(text);
        if (explicit.find()) {
            return Optional.of(explicit.group(1));
        }
        Matcher masked = MASKED_TAIL.matcher(text);
        if (masked.find()) {
            return Optional.of(masked.group(1));
        }
        Matcher vpa = VPA_PATTERN.matcher(text);
        while (vpa.find()) {
            // Distinguish a UPI handle from an email address. The pattern's
            // domain group stops at a dot, so "alerts@hdfcbank.com" matches as
            // "alerts@hdfcbank" and cannot be told apart by the captured text
            // alone. What settles it is the character immediately after the
            // match: a dot means a multi-label DNS name, i.e. an email address.
            int after = vpa.end();
            if (after < text.length() && text.charAt(after) == '.') {
                continue;
            }
            return Optional.of(vpa.group(0).toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------- reference

    /**
     * The provider's identifier for the event. Ordered most-specific first so
     * a UPI reference wins over a generic "reference number".
     */
    private static final List<Pattern> REFERENCE_PATTERNS = List.of(
            Pattern.compile("upi[\\s/-]*(?:ref(?:erence)?|txn|transaction)[\\s/-]*(?:no\\.?|id|number)?"
                    + "\\s*[:#-]?\\s*([a-z0-9]{6,25})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:transaction|txn)\\s*(?:ref(?:erence)?)?\\s*(?:no\\.?|id|number)"
                    + "\\s*[:#-]?\\s*([a-z0-9]{6,25})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ref(?:erence)?\\s*(?:no\\.?|id|number)?\\s*[:#-]\\s*([a-z0-9]{6,25})",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:order|invoice)\\s*(?:no\\.?|id|number|#)\\s*[:#-]?\\s*([a-z0-9-]{6,30})",
                    Pattern.CASE_INSENSITIVE));

    /**
     * Extracts the transaction reference.
     *
     * <p>This is the single most valuable field for deduplication: when two
     * sources both quote it, the matcher can link them with certainty rather
     * than by scoring. Worth the pattern list.
     */
    public static Optional<String> findReference(String text) {
        if (text == null) {
            return Optional.empty();
        }
        for (Pattern pattern : REFERENCE_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String reference = matcher.group(1).trim();
                // All-identical digits are a placeholder, not a reference.
                if (!reference.chars().allMatch(c -> c == reference.charAt(0))) {
                    return Optional.of(reference.toUpperCase(Locale.ROOT));
                }
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ type

    private static final Pattern REVERSAL_WORDS = Pattern.compile(
            "\\b(?:reversed|reversal|rolled\\s+back|failed\\s+transaction|transaction\\s+failed|"
                    + "chargeback|charge\\s*back|declined\\s+and\\s+refunded)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REFUND_WORDS = Pattern.compile(
            "\\b(?:refund(?:ed|s)?|money\\s+back|amount\\s+returned|returned\\s+to\\s+your)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CARD_PAYMENT_WORDS = Pattern.compile(
            "\\b(?:credit\\s+card\\s+(?:bill\\s+)?payment|payment\\s+(?:towards|received\\s+for)"
                    + "\\s+your\\s+(?:credit\\s+)?card|card\\s+bill\\s+paid|"
                    + "thank\\s+you\\s+for\\s+(?:your\\s+)?payment)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Debit indicators.
     *
     * <p>The bare word {@code debit} carries a negative lookahead for "card",
     * because "Debit Card" is an instrument name and not a statement that money
     * moved. Every alert about a debit card contains it.
     */
    private static final Pattern DEBIT_WORDS = Pattern.compile(
            "\\b(?:debited|spent|paid|withdrawn|purchase(?:d)?|charged"
                    + "|transferred\\s+to|sent\\s+to|payment\\s+of"
                    + "|debit(?!\\s*card))\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Credit indicators.
     *
     * <p>Same trap, worse consequences: "Credit Card" and "credit limit" appear
     * in the subject and body of essentially every credit card alert, so a bare
     * {@code credit} match classified every card purchase as income. The
     * lookahead excludes the instrument and the limit; the participle
     * {@code credited} is unambiguous and needs no guard.
     */
    private static final Pattern CREDIT_WORDS = Pattern.compile(
            "\\b(?:credited|received|deposited|added\\s+to\\s+your"
                    + "|credit(?!\\s*(?:card|limit|cards)))\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TRANSFER_WORDS = Pattern.compile(
            "\\b(?:self\\s+transfer|own\\s+account|transfer\\s+to\\s+your\\s+own)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Cash withdrawal wording.
     *
     * <p>Deliberately NOT classified as {@link TransactionType#TRANSFER}. A
     * transfer names two accounts and the schema requires a counter account,
     * but an ATM alert cannot say where the cash went — the user may not even
     * have a Cash account configured. Classifying it as a transfer would either
     * violate that constraint or require inventing a destination.
     *
     * <p>So it stays an {@code EXPENSE}, which is literally what the bank
     * reported (a debit), and the categorizer files it under
     * {@code transfer.cash}, which is marked {@code excludedFromSpending}. The
     * result is that withdrawing ₹5,000 does not appear as ₹5,000 of spending,
     * while the money leaving the account is still reflected in the balance.
     * What the cash is later spent on is genuinely unobservable and is reported
     * as a coverage gap rather than guessed at.
     */
    private static final Pattern CASH_WITHDRAWAL_WORDS = Pattern.compile(
            "\\b(?:atm\\s+(?:cash\\s+)?withdrawal|cash\\s+with(?:drawn|drawal)"
                    + "|withdrawn\\s+(?:at|from)\\s+(?:atm|a\\s+atm)"
                    + "|cash\\s+wdl|atm\\s+wdl)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * True when the message describes taking cash out rather than buying
     * something. See {@link #CASH_WITHDRAWAL_WORDS} for why this is kept
     * separate from the transaction type.
     */
    public static boolean isCashWithdrawal(String text) {
        return text != null && CASH_WITHDRAWAL_WORDS.matcher(text).find();
    }

    /**
     * Determines the transaction type from the wording.
     *
     * <p>Order matters and encodes the accounting rules. Reversal and refund
     * are checked before plain credit, because "your refund has been credited"
     * contains both and is a refund, not income. Card bill payment is checked
     * before debit, because it is a liability settlement rather than an
     * expense — getting that order wrong is what makes a tracker count every
     * card purchase twice.
     */
    public static Optional<TransactionType> findType(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        if (REVERSAL_WORDS.matcher(text).find()) {
            return Optional.of(TransactionType.REVERSAL);
        }
        if (REFUND_WORDS.matcher(text).find()) {
            return Optional.of(TransactionType.REFUND);
        }
        if (CARD_PAYMENT_WORDS.matcher(text).find()) {
            return Optional.of(TransactionType.CARD_PAYMENT);
        }
        if (TRANSFER_WORDS.matcher(text).find()) {
            return Optional.of(TransactionType.TRANSFER);
        }

        boolean debit = DEBIT_WORDS.matcher(text).find();
        boolean credit = CREDIT_WORDS.matcher(text).find();

        if (debit && !credit) {
            return Optional.of(TransactionType.EXPENSE);
        }
        if (credit && !debit) {
            // Both salary and a generic credit are INCOME. They are not
            // distinguished here because the difference is a *category*
            // ("Income / Salary"), which the categorizer decides from the
            // merchant with a confidence attached, not a transaction type.
            return Optional.of(TransactionType.INCOME);
        }
        if (debit && credit) {
            // Both appear, e.g. "debited from A/c and credited to beneficiary".
            // Whichever comes first describes the user's own account.
            Matcher debitMatcher = DEBIT_WORDS.matcher(text);
            Matcher creditMatcher = CREDIT_WORDS.matcher(text);
            int debitAt = debitMatcher.find() ? debitMatcher.start() : Integer.MAX_VALUE;
            int creditAt = creditMatcher.find() ? creditMatcher.start() : Integer.MAX_VALUE;
            return Optional.of(debitAt <= creditAt ? TransactionType.EXPENSE : TransactionType.INCOME);
        }

        // An authorisation alert carries no direction verb at all -- "Rs 5,000
        // has been authorized on your card ending 4567 at TAJ HOTELS" states
        // neither "debited" nor "credited". But a hold is always a pending
        // charge: nobody authorises money *into* an account. Inferring EXPENSE
        // here is what lets the later settlement alert match the hold instead
        // of creating a second expense for the same stay.
        if (looksPending(text)) {
            return Optional.of(TransactionType.EXPENSE);
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------- merchant

    /**
     * Phrases that introduce a merchant name. The capture deliberately stops at
     * a line break, a digit run or a sentence end, because these alerts run the
     * merchant straight into the next clause.
     */
    private static final List<Pattern> MERCHANT_PATTERNS = List.of(
            Pattern.compile("(?:at|to|towards)\\s+([A-Za-z0-9][A-Za-z0-9&'._*\\s-]{2,40}?)"
                    + "(?=\\s+(?:on|for|via|using|with|ref|upi|from)\\b|[.,;\\n]|$)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:merchant|payee|beneficiary|vpa)\\s*(?:name)?\\s*[:#-]\\s*"
                    + "([A-Za-z0-9][A-Za-z0-9&'._*@\\s-]{2,40})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:info|narration|particulars|remarks)\\s*[:#-]\\s*"
                    + "([A-Za-z0-9][A-Za-z0-9&'._*@/\\s-]{2,60})", Pattern.CASE_INSENSITIVE));

    /** Words that are never a merchant name on their own. */
    private static final Pattern MERCHANT_BLOCKLIST = Pattern.compile(
            "^(?:your|the|a|an|account|a/c|card|bank|upi|neft|imps|us|you|customer|"
                    + "your\\s+account|this)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Extracts the merchant string verbatim, for the normaliser to clean up.
     *
     * <p>Returns empty rather than a doubtful fragment. An absent merchant
     * shows up honestly as "Unknown" in reports, whereas a wrong one creates a
     * phantom entry in merchant-wise spending and may attract a category rule.
     */
    public static Optional<String> findMerchant(String text) {
        if (text == null) {
            return Optional.empty();
        }
        for (Pattern pattern : MERCHANT_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String candidate = matcher.group(1).trim()
                        .replaceAll("\\s{2,}", " ")
                        .replaceAll("[.,;:]+$", "");
                if (candidate.length() < 3 || MERCHANT_BLOCKLIST.matcher(candidate).matches()) {
                    continue;
                }
                // A candidate that is only digits is a reference, not a name.
                if (candidate.chars().filter(Character::isLetter).count() < 2) {
                    continue;
                }
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- status

    /**
     * Authorisation / hold wording.
     *
     * <p>Requires an affirmative construction. A bare {@code authoriz\w*} match
     * is useless here because almost every transaction alert ends with "if you
     * did not authorise this transaction, call us" — which would mark every
     * settled purchase as a pending hold, and then let the next genuine alert
     * "settle" it.
     */
    private static final Pattern PENDING_WORDS = Pattern.compile(
            "(?:(?:has\\s+been|was|is)\\s+authoris?z?\\w*"
                    + "|authoriz?s?ation\\s+(?:on|for|of)"
                    + "|pending\\s+settlement|awaiting\\s+settlement"
                    + "|temporary\\s+hold|hold\\s+placed|on\\s+hold"
                    + "|not\\s+yet\\s+posted|yet\\s+to\\s+be\\s+posted)",
            Pattern.CASE_INSENSITIVE);

    /** True when the message describes an authorisation rather than a posting. */
    public static boolean looksPending(String text) {
        return text != null && PENDING_WORDS.matcher(text).find();
    }

    /** Formatter for building a short description. */
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    /** Builds a concise, non-sensitive description for display. */
    public static String describe(TransactionType type, String merchant, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        sb.append(switch (type) {
            case EXPENSE -> "Payment";
            case INCOME -> "Credit";
            case REFUND -> "Refund";
            case REVERSAL -> "Reversal";
            case TRANSFER -> "Transfer";
            case CARD_PAYMENT -> "Card bill payment";
        });
        if (merchant != null && !merchant.isBlank()) {
            sb.append(" · ").append(merchant);
        }
        if (date != null) {
            sb.append(" · ").append(SHORT_DATE.format(date));
        }
        return sb.toString();
    }
}
