package com.ledger.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * A weighted score for "is this observation the same financial event as that
 * existing transaction?", together with the reasoning.
 *
 * <p>The rationale is not decoration. It is persisted on every
 * {@link MatchDecision} and shown in the review queue, so both the user and a
 * future maintainer can see exactly why two records were or were not merged.
 * A matcher that cannot explain itself cannot be trusted with someone's ledger.
 *
 * <h2>Evidence model</h2>
 * Each signal contributes a weighted component in [0,1]. The total is the
 * weighted mean over the signals that could actually be evaluated — a signal
 * the source did not provide is omitted from both numerator and denominator
 * rather than scored as zero. Scoring an absent field as a mismatch would make
 * a sparse-but-correct match look worse than a rich-but-wrong one.
 *
 * <h2>Vetoes</h2>
 * Some disagreements are disqualifying no matter how well everything else
 * lines up: a different currency, a different transaction type, or two
 * different known accounts. These set {@link #vetoed()} and the candidate is
 * discarded outright. This is what enforces "never merge records solely because
 * their amounts and dates match".
 */
public final class MatchScore {

    // Weights. Amount and account dominate: they are the signals least likely
    // to coincide by accident. Date is weighted lower because alert and
    // statement dates legitimately differ by a day or two.
    static final double WEIGHT_AMOUNT = 3.0;
    static final double WEIGHT_ACCOUNT = 2.5;
    static final double WEIGHT_MERCHANT = 2.0;
    static final double WEIGHT_DATE = 1.5;
    static final double WEIGHT_REFERENCE = 3.0;

    private final List<String> reasons = new ArrayList<>();
    private double weightedSum;
    private double totalWeight;
    private boolean vetoed;
    private String vetoReason;

    /**
     * Records a signal.
     *
     * @param name       signal name, for the rationale
     * @param weight     relative importance
     * @param similarity 0 (completely different) to 1 (identical)
     * @param detail     human-readable explanation, e.g. {@code "+1d"}
     */
    public MatchScore add(String name, double weight, double similarity, String detail) {
        double clamped = Math.clamp(similarity, 0.0, 1.0);
        weightedSum += weight * clamped;
        totalWeight += weight;
        reasons.add(name + "=" + detail);
        return this;
    }

    /** Records that a signal could not be evaluated, with no score effect. */
    public MatchScore skip(String name, String why) {
        reasons.add(name + "=" + why);
        return this;
    }

    /**
     * Disqualifies this candidate. The score becomes 0 regardless of other
     * evidence.
     */
    public MatchScore veto(String reason) {
        this.vetoed = true;
        this.vetoReason = reason;
        reasons.add("VETO(" + reason + ")");
        return this;
    }

    public boolean vetoed() {
        return vetoed;
    }

    public String vetoReason() {
        return vetoReason;
    }

    /**
     * The weighted mean of the evaluated signals, or 0 when vetoed or when no
     * signal could be evaluated at all.
     */
    public double value() {
        if (vetoed || totalWeight == 0) {
            return 0.0;
        }
        return weightedSum / totalWeight;
    }

    /** How much evidence went into the score, for tie-breaking. */
    public double evidenceWeight() {
        return totalWeight;
    }

    /** Human-readable reasoning, e.g. {@code "amount=exact, date=+1d, merchant=0.91"}. */
    public String rationale() {
        return String.join(", ", reasons);
    }

    @Override
    public String toString() {
        return "%.3f [%s]".formatted(value(), rationale());
    }
}
