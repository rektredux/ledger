package com.ledger.ingest;

import com.ledger.common.TextSimilarity;
import com.ledger.source.SourceRecord;
import com.ledger.source.SourceRecordRepository;
import com.ledger.txn.CanonicalTransaction;
import com.ledger.txn.CanonicalTransactionRepository;
import com.ledger.txn.TransactionStatus;
import com.ledger.txn.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether an observation is a new financial event or another sighting
 * of one already recorded.
 *
 * <p>This is the component that keeps a single coffee from becoming three
 * expenses when the merchant's receipt, the bank's instant alert and the
 * monthly statement all describe it. It is intentionally the most conservative
 * part of the application.
 *
 * <h2>Decision ladder</h2>
 * Strategies are tried strongest first and the first conclusive one wins:
 *
 * <ol>
 *   <li><b>Strong reference.</b> The provider supplied an identifier that names
 *       the financial event (a UPI reference, a bank transaction id) and an
 *       existing transaction carries the same one. Conclusive on its own.</li>
 *   <li><b>Content hash.</b> Byte-identical content to a record already
 *       ingested: a duplicate delivery or a forwarded copy.</li>
 *   <li><b>Pending settlement.</b> A posted observation that settles an
 *       existing authorisation on the same account, where the amount may
 *       legitimately have changed.</li>
 *   <li><b>Heuristic score.</b> A weighted judgement over amount, account,
 *       date, merchant and type. Only an unambiguous, clearly-best candidate
 *       above {@link #autoLinkThreshold} is linked automatically.</li>
 * </ol>
 *
 * <h2>Why the middle band exists</h2>
 * Between {@link #reviewThreshold} and {@link #autoLinkThreshold} the matcher
 * refuses to decide and returns {@link Outcome#review}. Two purchases of the
 * same amount at the same merchant on the same day are genuinely
 * indistinguishable from one purchase reported twice — buying two identical
 * coffees is a real thing people do. Guessing either way corrupts the ledger
 * silently, so the question is handed to the user with both candidates and the
 * reasoning attached.
 */
@Service
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    /**
     * How many days either side of the observed date to look for candidates.
     *
     * <p>Three days covers the usual lag between an instant alert and the
     * statement line, including a weekend. Widening it materially increases the
     * chance of colliding with a genuinely separate purchase.
     */
    private static final int CANDIDATE_WINDOW_DAYS = 3;

    /** Date window for finding a pending authorisation that a posting settles. */
    private static final int SETTLEMENT_WINDOW_DAYS = 10;

    /**
     * Amounts within one rupee are treated as equal.
     *
     * <p>Not zero, because the same transaction is routinely reported with
     * different rounding by different sources (a statement rounding a foreign
     * currency conversion, an alert omitting paise). One rupee is small enough
     * that two distinct purchases almost never fall inside it, and large enough
     * to absorb that rounding.
     */
    private static final long AMOUNT_TOLERANCE_MINOR = 100L;

    /**
     * Merchant similarity below which two observations are ruled out as the
     * same event.
     *
     * <p>Set low (0.40) so it only fires on names that are genuinely unalike.
     * Legitimate variants of one merchant — {@code "Swiggy"} vs
     * {@code "Swiggy Instamart"}, {@code "Uber"} vs {@code "Uber India"} —
     * score well above it thanks to the containment rule in
     * {@link TextSimilarity}.
     */
    private static final double MERCHANT_VETO_THRESHOLD = 0.40;

    private final CanonicalTransactionRepository transactions;

    /** Used to find out which parsers already evidence a candidate. */
    private final SourceRecordRepository sourceRecords;

    /** At or above this score, link automatically. */
    private final double autoLinkThreshold;

    /** At or above this score (but below auto-link), ask the user. */
    private final double reviewThreshold;

    /**
     * How far the best candidate must beat the runner-up to be acted on
     * automatically. Two near-equal candidates mean the evidence does not
     * actually distinguish them, however high the top score is.
     */
    private final double ambiguityMargin;

    public MatchingEngine(CanonicalTransactionRepository transactions,
                          SourceRecordRepository sourceRecords,
                          @Value("${ledger.match.auto-link-threshold:0.90}") double autoLinkThreshold,
                          @Value("${ledger.match.review-threshold:0.65}") double reviewThreshold,
                          @Value("${ledger.match.ambiguity-margin:0.08}") double ambiguityMargin) {
        this.transactions = transactions;
        this.sourceRecords = sourceRecords;
        this.autoLinkThreshold = autoLinkThreshold;
        this.reviewThreshold = reviewThreshold;
        this.ambiguityMargin = ambiguityMargin;
    }

    /** What the matcher concluded. */
    public enum Action {
        /** No acceptable candidate: create a new canonical transaction. */
        CREATE_NEW,
        /** Attach to an existing transaction as additional evidence. */
        LINK_EXISTING,
        /** Update an existing pending transaction with posted details. */
        SETTLE_PENDING,
        /** Inconclusive: hand to the user. Nothing is merged. */
        REVIEW,
        /** Byte-identical content already ingested. */
        DUPLICATE_CONTENT
    }

    /**
     * The matcher's conclusion.
     *
     * @param action    what to do
     * @param target    the matched transaction, for every action except CREATE_NEW
     * @param strategy  how the conclusion was reached, for the audit trail
     * @param score     heuristic score, when one was computed
     * @param rationale human-readable reasoning; always populated
     * @param candidates scored alternatives, for the review queue
     */
    public record Outcome(Action action,
                          CanonicalTransaction target,
                          MatchDecision.Strategy strategy,
                          Double score,
                          String rationale,
                          List<ScoredCandidate> candidates) {

        static Outcome createNew(String rationale, List<ScoredCandidate> candidates) {
            return new Outcome(Action.CREATE_NEW, null, MatchDecision.Strategy.HEURISTIC_SCORE,
                    null, rationale, candidates);
        }

        static Outcome link(CanonicalTransaction target, MatchDecision.Strategy strategy,
                            Double score, String rationale) {
            return new Outcome(Action.LINK_EXISTING, target, strategy, score, rationale, List.of());
        }

        static Outcome settle(CanonicalTransaction target, Double score, String rationale) {
            return new Outcome(Action.SETTLE_PENDING, target, MatchDecision.Strategy.HEURISTIC_SCORE,
                    score, rationale, List.of());
        }

        static Outcome review(String rationale, List<ScoredCandidate> candidates) {
            return new Outcome(Action.REVIEW, null, MatchDecision.Strategy.HEURISTIC_SCORE,
                    candidates.isEmpty() ? null : candidates.get(0).score(), rationale, candidates);
        }

        static Outcome duplicateContent(CanonicalTransaction target, String rationale) {
            return new Outcome(Action.DUPLICATE_CONTENT, target, MatchDecision.Strategy.CONTENT_HASH,
                    1.0, rationale, List.of());
        }
    }

    /** A candidate transaction with its score and reasoning. */
    public record ScoredCandidate(CanonicalTransaction transaction, double score, String rationale) {
    }

    /**
     * Matches a parsed source record against the existing ledger.
     *
     * @param record       the observation, which must have passed
     *                     {@link SourceRecord#hasMinimumFields()}
     * @param resolvedAccountId the account the record was attributed to, or null
     * @param duplicateContentTarget the transaction linked to an
     *     already-ingested record with identical content, or null
     */
    public Outcome match(SourceRecord record, String resolvedAccountId,
                         CanonicalTransaction duplicateContentTarget) {

        // --- 1. Byte-identical content ------------------------------------
        if (duplicateContentTarget != null) {
            return Outcome.duplicateContent(duplicateContentTarget,
                    "identical content already ingested as transaction " + duplicateContentTarget.getId());
        }

        // --- 2. Strong reference ------------------------------------------
        String reference = trimToNull(record.getExtractedReference());
        if (reference != null) {
            Optional<CanonicalTransaction> byReference = findByReference(reference, resolvedAccountId);
            if (byReference.isPresent()) {
                return Outcome.link(byReference.get(), MatchDecision.Strategy.STRONG_REFERENCE, 1.0,
                        "reference=exact(" + reference + ")");
            }
        }

        long amountMinor = record.getExtractedAmountMinor();
        String currency = record.getExtractedCurrency() == null ? "INR" : record.getExtractedCurrency();
        LocalDate observedOn = record.getExtractedOccurredOn();
        TransactionType type = record.getExtractedType();

        // --- 3. Pending settlement ----------------------------------------
        Optional<Outcome> settlement = findPendingSettlement(record, resolvedAccountId, observedOn, type);
        if (settlement.isPresent()) {
            return settlement.get();
        }

        // --- 4. Heuristic scoring -----------------------------------------
        List<CanonicalTransaction> pool = transactions.findMatchCandidates(
                amountMinor, currency,
                observedOn.minusDays(CANDIDATE_WINDOW_DAYS),
                observedOn.plusDays(CANDIDATE_WINDOW_DAYS),
                resolvedAccountId);

        List<ScoredCandidate> scored = new ArrayList<>();
        for (CanonicalTransaction candidate : pool) {
            MatchScore score = score(record, resolvedAccountId, candidate);
            if (!score.vetoed()) {
                scored.add(new ScoredCandidate(candidate, score.value(), score.rationale()));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());

        if (scored.isEmpty()) {
            return Outcome.createNew("no candidate within "
                    + CANDIDATE_WINDOW_DAYS + "d at the same amount", List.of());
        }

        ScoredCandidate best = scored.get(0);
        double runnerUp = scored.size() > 1 ? scored.get(1).score() : 0.0;
        boolean clearWinner = (best.score() - runnerUp) >= ambiguityMargin;

        boolean corroborating = isIndependentCorroboration(record, best.transaction());

        if (best.score() >= autoLinkThreshold && clearWinner && corroborating) {
            return Outcome.link(best.transaction(), MatchDecision.Strategy.HEURISTIC_SCORE,
                    best.score(), best.rationale());
        }

        if (best.score() >= reviewThreshold) {
            String why;
            if (!corroborating) {
                why = "the same source already reported this transaction and no shared reference "
                        + "distinguishes a repeat purchase from a repeated alert";
            } else if (best.score() >= autoLinkThreshold) {
                why = "two candidates scored within " + ambiguityMargin + "; cannot distinguish them";
            } else {
                why = "best score " + round(best.score()) + " is below the auto-link threshold "
                        + autoLinkThreshold;
            }
            return Outcome.review(why + " [" + best.rationale() + "]", scored);
        }

        return Outcome.createNew("best candidate scored only " + round(best.score())
                + ", below the review threshold " + reviewThreshold, scored);
    }

    /**
     * Whether this observation is an <em>independent</em> sighting of the
     * candidate, rather than the same source speaking twice.
     *
     * <p>This is the safeguard against merging two genuinely separate
     * purchases. Consider two coffees at the same shop, same amount, same card,
     * same day. Every signal the scorer has agrees perfectly, so the score is
     * near 1.0 — and merging them would silently delete a real expense.
     *
     * <p>What distinguishes "one purchase reported twice" from "two identical
     * purchases" is <em>who is reporting</em>. Two different sources (a
     * merchant receipt and a bank alert) describing the same amount on the same
     * day is overwhelmingly one event: the merchant and the bank do not
     * coincidentally bill the same sum. The same source reporting it twice is
     * the opposite — genuine re-delivery is already caught upstream by the
     * provider-id and content-hash checks, so a second message from the same
     * parser is more likely to be a second purchase.
     *
     * <p>A shared external reference overrides all of this: when both sightings
     * quote the same identifier, they name the same financial event regardless
     * of origin.
     */
    private boolean isIndependentCorroboration(SourceRecord record, CanonicalTransaction candidate) {
        String reference = trimToNull(record.getExtractedReference());
        if (reference != null && reference.equalsIgnoreCase(candidate.getExternalReference())) {
            return true;
        }
        if (record.getParserId() == null) {
            // Origin unknown; fall back to treating it as independent rather
            // than blocking a legitimate link.
            return true;
        }
        List<SourceRecord> existingEvidence = sourceRecords.findByCanonicalTransactionId(candidate.getId());
        if (existingEvidence.isEmpty()) {
            return true;
        }
        return existingEvidence.stream()
                .map(SourceRecord::getParserId)
                .noneMatch(parserId -> record.getParserId().equals(parserId));
    }

    private Optional<CanonicalTransaction> findByReference(String reference, String accountId) {
        if (accountId != null) {
            Optional<CanonicalTransaction> scoped =
                    transactions.findByAccountIdAndExternalReference(accountId, reference);
            if (scoped.isPresent()) {
                return scoped;
            }
        }
        // A reference seen before an account could be attributed still names the
        // same event. Only accept it when exactly one transaction carries it;
        // more than one means the reference is not as unique as assumed.
        List<CanonicalTransaction> matches = transactions.findByExternalReference(reference);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /**
     * Looks for an existing authorisation that this observation settles.
     *
     * <p>Only applies to a posted expense on a known account. The amount is not
     * required to match, because an authorisation routinely posts for a
     * different figure, but the merchant must agree strongly — without that,
     * any posting would settle any pending transaction on the account.
     */
    private Optional<Outcome> findPendingSettlement(SourceRecord record, String accountId,
                                                    LocalDate observedOn, TransactionType type) {
        if (accountId == null || type != TransactionType.EXPENSE) {
            return Optional.empty();
        }
        List<CanonicalTransaction> pending = transactions.findPendingForSettlement(
                accountId,
                observedOn.minusDays(SETTLEMENT_WINDOW_DAYS),
                observedOn);
        if (pending.isEmpty()) {
            return Optional.empty();
        }

        String merchant = trimToNull(record.getExtractedMerchant());
        for (CanonicalTransaction candidate : pending) {
            if (candidate.getType() != TransactionType.EXPENSE) {
                continue;
            }
            double merchantSimilarity = TextSimilarity.similarity(merchant, candidate.getMerchant());
            boolean amountClose = Math.abs(candidate.getAmountMinor() - record.getExtractedAmountMinor())
                    <= Math.max(AMOUNT_TOLERANCE_MINOR, candidate.getAmountMinor() / 5);
            if (merchantSimilarity >= 0.85 && amountClose) {
                String rationale = "settles pending authorisation: merchant=" + round(merchantSimilarity)
                        + ", pending=" + candidate.amount() + ", posted="
                        + record.extractedAmount();
                return Optional.of(Outcome.settle(candidate, merchantSimilarity, rationale));
            }
        }
        return Optional.empty();
    }

    /**
     * Scores one candidate against the observation.
     *
     * <p>Vetoes come first: a disagreement on currency, type or a known account
     * is disqualifying regardless of how well the rest lines up. This is the
     * mechanism behind "never merge records solely because their amounts and
     * dates match".
     */
    MatchScore score(SourceRecord record, String resolvedAccountId, CanonicalTransaction candidate) {
        MatchScore score = new MatchScore();

        // --- Vetoes --------------------------------------------------------
        String currency = record.getExtractedCurrency() == null ? "INR" : record.getExtractedCurrency();
        if (!currency.equalsIgnoreCase(candidate.getCurrency())) {
            return score.veto("currency " + currency + " vs " + candidate.getCurrency());
        }
        if (record.getExtractedType() != candidate.getType()) {
            // An expense and a refund of the same amount on the same day are
            // related but are emphatically not the same event.
            return score.veto("type " + record.getExtractedType() + " vs " + candidate.getType());
        }
        if (resolvedAccountId != null && candidate.getAccountId() != null
                && !resolvedAccountId.equals(candidate.getAccountId())) {
            return score.veto("different accounts");
        }
        if (candidate.getStatus() == TransactionStatus.VOID) {
            return score.veto("candidate is void");
        }

        // --- Amount --------------------------------------------------------
        long delta = Math.abs(candidate.getAmountMinor() - record.getExtractedAmountMinor());
        if (delta == 0) {
            score.add("amount", MatchScore.WEIGHT_AMOUNT, 1.0, "exact");
        } else if (delta <= AMOUNT_TOLERANCE_MINOR) {
            score.add("amount", MatchScore.WEIGHT_AMOUNT, 0.9, "within tolerance");
        } else {
            // Outside tolerance the candidate pool would not have returned it,
            // but a settlement path or a widened query could.
            score.add("amount", MatchScore.WEIGHT_AMOUNT, 0.0, "differs by " + delta + " minor units");
        }

        // --- Account -------------------------------------------------------
        if (resolvedAccountId == null || candidate.getAccountId() == null) {
            // Neither side can confirm the instrument. Not evidence either way,
            // so it is excluded from the mean rather than scored as a mismatch.
            score.skip("account", "unknown");
        } else {
            score.add("account", MatchScore.WEIGHT_ACCOUNT, 1.0, "exact");
        }

        // --- Date ----------------------------------------------------------
        long daysApart = Math.abs(observedMinusCandidate(record.getExtractedOccurredOn(),
                candidate.getOccurredOn()));
        double dateSimilarity = switch ((int) Math.min(daysApart, 4)) {
            case 0 -> 1.0;
            case 1 -> 0.85;
            case 2 -> 0.6;
            case 3 -> 0.35;
            default -> 0.1;
        };
        score.add("date", MatchScore.WEIGHT_DATE, dateSimilarity,
                daysApart == 0 ? "same day" : daysApart + "d apart");

        // --- Merchant ------------------------------------------------------
        String merchant = trimToNull(record.getExtractedMerchant());
        if (merchant == null || candidate.getMerchant() == null) {
            score.skip("merchant", "unknown");
        } else {
            double similarity = TextSimilarity.similarity(merchant, candidate.getMerchant());
            if (similarity < MERCHANT_VETO_THRESHOLD) {
                // Unlike a reference, a merchant name is not namespaced: every
                // source is naming the same real-world business. So when both
                // sides state one and they are nothing alike, this is a
                // different event, however well the amount and date agree.
                //
                // Without this veto, a ₹500 Swiggy order and a ₹500 Netflix
                // charge on the same card on the same day score high enough on
                // amount + account + date alone to land in the review queue,
                // burying the user in questions with obvious answers.
                return score.veto("different merchants (" + round(similarity) + ")");
            }
            score.add("merchant", MatchScore.WEIGHT_MERCHANT, similarity, round(similarity));
        }

        // --- Reference -----------------------------------------------------
        String reference = trimToNull(record.getExtractedReference());
        if (reference == null || candidate.getExternalReference() == null) {
            score.skip("reference", "unknown");
        } else if (reference.equalsIgnoreCase(candidate.getExternalReference())) {
            score.add("reference", MatchScore.WEIGHT_REFERENCE, 1.0, "exact");
        } else {
            // Differing references are NOT a veto, and not a zero either: the
            // signal is skipped.
            //
            // Identifiers are minted per source, not per financial event. One
            // purchase legitimately carries a merchant order number on the
            // receipt ("SW12345678") and a bank UPI reference on the alert
            // ("UPI987654321098"). Vetoing on the mismatch made the two
            // sightings of one purchase un-mergeable, which is exactly the
            // double-counting this engine exists to prevent. Scoring it zero
            // was no better: it dragged a perfect match below the threshold.
            //
            // A reference is therefore positive evidence only. Its absence or
            // disagreement tells us nothing, so it is excluded from the mean.
            score.skip("reference", "different identifier namespaces");
        }

        return score;
    }

    private static long observedMinusCandidate(LocalDate observed, LocalDate candidate) {
        return observed.toEpochDay() - candidate.toEpochDay();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String round(double value) {
        return "%.2f".formatted(value);
    }

    public double autoLinkThreshold() {
        return autoLinkThreshold;
    }

    public double reviewThreshold() {
        return reviewThreshold;
    }
}
