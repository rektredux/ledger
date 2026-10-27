package com.ledger.categorize;

import com.ledger.category.CategoryService;
import com.ledger.txn.CategorySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Assigns categories using local rules only.
 *
 * <p>No external service, no model inference, nothing leaves the machine. The
 * engine is a priority-ordered rule list evaluated against the normalised
 * merchant name and, failing that, the description. That is enough to
 * categorise everyday purchases automatically, which is the actual requirement
 * — a user should not be asked to file their morning coffee.
 *
 * <h2>Why confidence is carried through</h2>
 * A rule match is a guess about intent, not an observation. {@code "amazon"}
 * genuinely could be groceries, electronics or a book, so its built-in rule
 * carries 0.80 rather than 0.95. The confidence travels onto the transaction so
 * the UI can distinguish a near-certain match from a plausible one, and so the
 * threshold below can route genuinely unclear cases to review instead of
 * guessing.
 *
 * <h2>Learning</h2>
 * When a user recategorises a transaction, {@link #learnFrom} writes a
 * {@code LEARNED} rule at a higher priority than any built-in. The next
 * transaction from that merchant is categorised the user's way. Learning is
 * deliberately limited to an exact match on the normalised merchant name:
 * generalising further (inferring a pattern from one correction) would
 * recategorise transactions the user never asked about.
 */
@Service
public class CategorizationEngine {

    private static final Logger log = LoggerFactory.getLogger(CategorizationEngine.class);

    private final CategoryRuleRepository rules;
    private final CategoryService categories;

    /**
     * Minimum confidence to accept an automatic category. Below this the
     * transaction is stored uncategorized and queued for review rather than
     * being filed somewhere plausible-but-wrong.
     */
    private final double minimumConfidence;

    /** Snapshot of enabled rules, best first. Rebuilt on mutation. */
    private volatile List<CategoryRule> ruleCache;

    public CategorizationEngine(CategoryRuleRepository rules,
                                CategoryService categories,
                                @Value("${ledger.categorize.min-confidence:0.5}") double minimumConfidence) {
        this.rules = rules;
        this.categories = categories;
        this.minimumConfidence = minimumConfidence;
    }

    /**
     * The outcome of categorising one transaction.
     *
     * @param categoryId the chosen category, or
     *     {@link CategoryService#UNCATEGORIZED_ID} when nothing matched
     * @param source     how the decision was reached
     * @param confidence 0 to 1; exactly 1 only for a user decision
     * @param ruleId     the rule that fired, for the audit trail; null if none
     * @param needsReview true when the result should be queued for a human
     */
    public record Categorization(String categoryId,
                                 CategorySource source,
                                 double confidence,
                                 String ruleId,
                                 boolean needsReview) {

        static Categorization uncategorized() {
            return new Categorization(CategoryService.UNCATEGORIZED_ID,
                    CategorySource.UNCATEGORIZED, 0.0, null, true);
        }

        public boolean isCategorized() {
            return source != CategorySource.UNCATEGORIZED;
        }
    }

    public void invalidate() {
        this.ruleCache = null;
    }

    private List<CategoryRule> activeRules() {
        List<CategoryRule> cached = ruleCache;
        if (cached == null) {
            cached = rules.findByEnabledTrueOrderByPriorityDesc().stream()
                    .sorted(Comparator.comparingInt(CategoryRule::rank).reversed())
                    .toList();
            ruleCache = cached;
        }
        return cached;
    }

    /**
     * Categorises a transaction from its normalised merchant and description.
     *
     * <p>The merchant is tried first and the description only as a fallback:
     * descriptions contain rail noise that produces false positives (a
     * description mentioning "transfer to Amazon Pay" should not become
     * shopping).
     */
    public Categorization categorize(String normalizedMerchant, String description) {
        Optional<CategoryRule> merchantMatch = firstMatch(normalizedMerchant);
        if (merchantMatch.isPresent()) {
            return accept(merchantMatch.get());
        }
        Optional<CategoryRule> descriptionMatch = firstMatch(description);
        if (descriptionMatch.isPresent()) {
            CategoryRule rule = descriptionMatch.get();
            // A description match is weaker evidence than a merchant match.
            double discounted = rule.getConfidence() * 0.8;
            if (discounted < minimumConfidence) {
                return Categorization.uncategorized();
            }
            return new Categorization(rule.getCategoryId(), sourceFor(rule), discounted, rule.getId(), false);
        }
        return Categorization.uncategorized();
    }

    private Optional<CategoryRule> firstMatch(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (CategoryRule rule : activeRules()) {
            if (rule.matches(value)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private Categorization accept(CategoryRule rule) {
        if (rule.getConfidence() < minimumConfidence) {
            return Categorization.uncategorized();
        }
        return new Categorization(rule.getCategoryId(), sourceFor(rule),
                rule.getConfidence(), rule.getId(), false);
    }

    private static CategorySource sourceFor(CategoryRule rule) {
        return rule.getOrigin() == CategoryRule.Origin.LEARNED
                ? CategorySource.LEARNED
                : CategorySource.RULE;
    }

    /**
     * Records that the user wants {@code merchant} filed under
     * {@code categoryId}, so future transactions are categorised that way.
     *
     * <p>Creates or updates a {@code LEARNED} rule keyed on an exact match of
     * the normalised merchant name. Exact rather than substring: a correction
     * about "Amazon Fresh" must not silently recategorise "Amazon" purchases
     * the user never looked at.
     *
     * <p>A no-op when the merchant is unknown — there is nothing to key a rule
     * on, and the correction applies to that one transaction only.
     */
    @Transactional
    public Optional<CategoryRule> learnFrom(String normalizedMerchant, String categoryId) {
        if (normalizedMerchant == null || normalizedMerchant.isBlank() || categoryId == null) {
            return Optional.empty();
        }
        if (categories.find(categoryId).isEmpty()) {
            throw new IllegalArgumentException("unknown category: " + categoryId);
        }

        String pattern = normalizedMerchant.trim().toLowerCase();
        CategoryRule rule = rules
                .findByPatternAndMatchTypeAndOrigin(pattern, MatchType.EXACT, CategoryRule.Origin.LEARNED)
                .orElseGet(() -> new CategoryRule(pattern, MatchType.EXACT, categoryId,
                        CategoryRule.Origin.LEARNED));
        rule.setCategoryId(categoryId);
        rule.setEnabled(true);
        // A correction is a direct statement of intent for this merchant, so the
        // learned rule is trusted fully for subsequent transactions.
        rule.setConfidence(1.0);
        rule.setPriority(CategoryRule.PRIORITY_LEARNED);

        CategoryRule saved = rules.save(rule);
        invalidate();
        log.info("Learned category rule for merchant pattern (category={})", categoryId);
        return Optional.of(saved);
    }

    // --- Rule administration ------------------------------------------------

    @Transactional
    public CategoryRule createUserRule(String pattern, MatchType matchType, String categoryId, double confidence) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("rule pattern is required");
        }
        if (categories.find(categoryId).isEmpty()) {
            throw new IllegalArgumentException("unknown category: " + categoryId);
        }
        if (matchType == MatchType.REGEX) {
            // Fail now, with a clear message, rather than silently never
            // matching at evaluation time.
            try {
                java.util.regex.Pattern.compile(pattern);
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new IllegalArgumentException("invalid regular expression: " + e.getDescription());
            }
        }
        CategoryRule rule = new CategoryRule(pattern.trim().toLowerCase(), matchType, categoryId,
                CategoryRule.Origin.USER);
        rule.setConfidence(Math.clamp(confidence, 0.0, 1.0));
        CategoryRule saved = rules.save(rule);
        invalidate();
        return saved;
    }

    @Transactional
    public void deleteRule(String id) {
        CategoryRule rule = rules.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown rule: " + id));
        if (rule.getOrigin() == CategoryRule.Origin.BUILTIN) {
            // Built-ins are part of the shipped seed data and would reappear on
            // a fresh install, so disabling is the honest operation.
            rule.setEnabled(false);
            rules.save(rule);
        } else {
            rules.delete(rule);
        }
        invalidate();
    }

    @Transactional
    public CategoryRule setEnabled(String id, boolean enabled) {
        CategoryRule rule = rules.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown rule: " + id));
        rule.setEnabled(enabled);
        CategoryRule saved = rules.save(rule);
        invalidate();
        return saved;
    }

    public List<CategoryRule> allRules() {
        return rules.findAllByOrderByPriorityDescCreatedAtAsc();
    }

    /** Increments the hit counter so unused rules are visible in Settings. */
    @Transactional
    public void recordHit(String ruleId) {
        if (ruleId == null) {
            return;
        }
        rules.findById(ruleId).ifPresent(rule -> {
            rule.recordHit();
            rules.save(rule);
        });
    }
}
