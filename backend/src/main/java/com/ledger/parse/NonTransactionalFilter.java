package com.ledger.parse;

import com.ledger.ingest.RawObservation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Recognises financial mail that contains amounts but describes no transaction.
 *
 * <p>A bank's mail stream is mostly not transactions: OTPs, statement
 * availability notices, due date reminders, promotional offers, KYC nags. All
 * of these quote rupee amounts, and several quote amounts in exactly the
 * phrasing a transaction alert uses. Without this filter, a "minimum amount due
 * ₹4,500" reminder becomes a ₹4,500 expense that the user never spent.
 *
 * <p>Matching here produces an {@code IGNORED} source record: the message is
 * recorded so the same mail is not re-examined on every sync, the reason is
 * kept, and the user can see what was filtered. Nothing is deleted.
 *
 * <p>This runs <em>before</em> the parsers, and deliberately errs toward
 * letting things through: a false positive here silently loses a real
 * transaction, whereas a false negative merely means a parser declines the
 * message or it lands in the exception queue where it is visible.
 */
@Component
public class NonTransactionalFilter {

    /**
     * Each entry is a reason plus the pattern that detects it. Patterns are
     * anchored on distinctive phrasing rather than single keywords: "statement"
     * alone appears in genuine transaction alerts ("will appear in your next
     * statement"), so it cannot be a filter on its own.
     */
    private record Rule(String reason, Pattern pattern) {
    }

    /**
     * Rules that nothing can override.
     *
     * <p>An OTP mail names an amount and the transaction it authorises, in
     * wording close enough to a real alert that the override below would let it
     * through. It is never itself a completed transaction: the money has not
     * moved yet, and if it does, a separate alert will say so. Letting one
     * through produces a phantom expense for a payment that may have been
     * abandoned.
     */
    private static final List<Rule> ABSOLUTE_RULES = List.of(
            new Rule("one-time password", Pattern.compile(
                    "\\b(?:otp|one[\\s-]?time\\s*password|verification\\s+code)\\b",
                    Pattern.CASE_INSENSITIVE)));

    private static final List<Rule> RULES = List.of(
            new Rule("statement availability notice", Pattern.compile(
                    "\\b(?:your\\s+(?:monthly\\s+)?(?:account\\s+|credit\\s+card\\s+)?statement\\s+"
                            + "(?:is\\s+)?(?:now\\s+)?(?:ready|available|attached|generated|enclosed)"
                            + "|e-?statement\\s+for\\s+the\\s+(?:month|period))\\b",
                    Pattern.CASE_INSENSITIVE)),

            new Rule("payment due reminder", Pattern.compile(
                    "\\b(?:(?:payment|bill)\\s+(?:is\\s+)?due\\s+(?:on|by)"
                            + "|kindly\\s+pay\\s+(?:your|the)"
                            + "|please\\s+pay\\s+(?:your|the)"
                            + "|due\\s+date\\s+(?:is|for\\s+your)"
                            + "|avoid\\s+late\\s+payment\\s+charges"
                            + "|reminder\\s*:?\\s*your\\s+.{0,30}\\s*bill)\\b",
                    Pattern.CASE_INSENSITIVE)),

            new Rule("promotional offer", Pattern.compile(
                    "\\b(?:pre-?approved|exclusive\\s+offer|limited\\s+time\\s+offer|apply\\s+now"
                            + "|upgrade\\s+your\\s+card|you\\s+are\\s+eligible\\s+for"
                            + "|instant\\s+loan|personal\\s+loan\\s+offer|cashback\\s+offer"
                            + "|unsubscribe\\s+from\\s+promotional)\\b",
                    Pattern.CASE_INSENSITIVE)),

            new Rule("balance or summary notification", Pattern.compile(
                    "\\b(?:available\\s+balance\\s+in\\s+your\\s+account\\s+is"
                            + "|your\\s+account\\s+balance\\s+(?:is|as\\s+on)"
                            + "|monthly\\s+(?:account\\s+)?summary)\\b",
                    Pattern.CASE_INSENSITIVE)),

            new Rule("security or service notice", Pattern.compile(
                    "\\b(?:kyc|re-?kyc|update\\s+your\\s+(?:pan|aadhaar|address|mobile\\s+number)"
                            + "|password\\s+(?:changed|reset)|login\\s+(?:alert|attempt)"
                            + "|beneficiary\\s+(?:added|registered)|cheque\\s+book\\s+request"
                            + "|nominee\\s+(?:added|update))\\b",
                    Pattern.CASE_INSENSITIVE)),

            new Rule("autopay or mandate setup", Pattern.compile(
                    "\\b(?:mandate\\s+(?:registered|created|successfully\\s+set)"
                            + "|autopay\\s+(?:set\\s*up|registered|enabled)"
                            + "|standing\\s+instruction\\s+(?:registered|set))\\b",
                    Pattern.CASE_INSENSITIVE)));

    /**
     * Transaction wording strong enough to override a filter rule.
     *
     * <p>Banks routinely append a due-date reminder or a promotional footer to a
     * real transaction alert. When a message says money actually moved, that
     * takes precedence over any boilerplate elsewhere in it.
     */
    /**
     * Wording that asserts money actually moved.
     *
     * <p>Every alternative here is a completed action in the past tense about
     * the user's own account. Weaker phrasings were tried and removed: {@code
     * "transaction of Rs X"} in particular appears in OTP mails and due-date
     * reminders as often as in real alerts, so using it as an override let both
     * through.
     */
    private static final Pattern DEFINITE_TRANSACTION = Pattern.compile(
            "\\b(?:has\\s+been\\s+(?:debited|credited)|was\\s+(?:debited|credited)"
                    + "|(?:debited|credited)\\s+(?:from|to)\\s+your"
                    + "|you\\s+(?:have\\s+)?(?:spent|paid|received)"
                    + "|successfully\\s+(?:paid|transferred))\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Why this message should be ignored, or empty when it should be parsed.
     *
     * <p>Order: absolute rules, then the transaction override, then the
     * overridable rules. The override exists because banks append due-date
     * reminders and promotional footers to genuine transaction alerts, and a
     * message that says money moved is a transaction regardless of what else it
     * contains.
     */
    public Optional<String> reasonToIgnore(RawObservation observation) {
        String text = observation.searchableText();
        if (text.isBlank()) {
            return Optional.of("empty message");
        }

        for (Rule rule : ABSOLUTE_RULES) {
            if (rule.pattern().matcher(text).find()) {
                return Optional.of(rule.reason());
            }
        }

        if (DEFINITE_TRANSACTION.matcher(text).find()) {
            return Optional.empty();
        }

        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return Optional.of(rule.reason());
            }
        }
        return Optional.empty();
    }

    /** Convenience predicate. */
    public boolean shouldIgnore(RawObservation observation) {
        return reasonToIgnore(observation).isPresent();
    }
}
