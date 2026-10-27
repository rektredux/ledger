package com.ledger.categorize;

import com.ledger.common.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A merchant-to-category rule.
 *
 * <p>Categorization is entirely local and rule-based: no external API, no model
 * inference, no transaction data leaving the machine. Rules are plain data, so
 * the user can read, edit and delete every one of them in Settings.
 *
 * <p>Priority bands, highest wins:
 * <ul>
 *   <li>{@code 300} — {@link Origin#USER}, authored by hand.</li>
 *   <li>{@code 200} — {@link Origin#LEARNED}, written automatically when the
 *       user recategorizes a transaction.</li>
 *   <li>{@code 100} — {@link Origin#BUILTIN}, shipped with the application.</li>
 * </ul>
 * A user correction therefore always outranks a shipped default, which is what
 * makes "learn from my correction" actually hold.
 */
@Entity
@Table(name = "category_rule")
public class CategoryRule {

    /** Priority assigned to hand-authored rules. */
    public static final int PRIORITY_USER = 300;
    /** Priority assigned to rules learned from user corrections. */
    public static final int PRIORITY_LEARNED = 200;
    /** Priority assigned to rules shipped with the application. */
    public static final int PRIORITY_BUILTIN = 100;

    public enum Origin {
        /** Shipped in the seed migration. */
        BUILTIN,
        /** Written by the app after the user corrected a category. */
        LEARNED,
        /** Authored by the user in Settings. */
        USER
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    /** Compared against the normalized merchant name, then the description. */
    @Column(name = "pattern", nullable = false)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false)
    private MatchType matchType = MatchType.CONTAINS;

    @Column(name = "category_id", nullable = false)
    private String categoryId;

    @Column(name = "priority", nullable = false)
    private int priority = PRIORITY_BUILTIN;

    /**
     * Confidence assigned to transactions this rule categorizes, 0 to 1.
     * Carried onto the transaction so the UI can mark a low-confidence guess.
     */
    @Column(name = "confidence", nullable = false)
    private double confidence = 0.9;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false)
    private Origin origin = Origin.BUILTIN;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** How often this rule has fired, so dead rules are visible in Settings. */
    @Column(name = "hit_count", nullable = false)
    private long hitCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CategoryRule() {
        // for JPA
    }

    public CategoryRule(String pattern, MatchType matchType, String categoryId, Origin origin) {
        this.pattern = pattern;
        this.matchType = matchType;
        this.categoryId = categoryId;
        this.origin = origin;
        this.priority = defaultPriorityFor(origin);
    }

    public static int defaultPriorityFor(Origin origin) {
        return switch (origin) {
            case USER -> PRIORITY_USER;
            case LEARNED -> PRIORITY_LEARNED;
            case BUILTIN -> PRIORITY_BUILTIN;
        };
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** True when this rule matches the given value. */
    public boolean matches(String value) {
        return enabled && matchType.matches(pattern, value);
    }

    /**
     * Ranking score for choosing between rules that all matched. Priority
     * dominates; pattern length and match specificity break ties so that
     * "swiggy instamart" beats a bare "swiggy".
     */
    public int rank() {
        return priority * 1000 + matchType.specificityBonus() + (pattern == null ? 0 : pattern.length());
    }

    public void recordHit() {
        this.hitCount++;
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public void setMatchType(MatchType matchType) {
        this.matchType = matchType;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public Origin getOrigin() {
        return origin;
    }

    public void setOrigin(Origin origin) {
        this.origin = origin;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getHitCount() {
        return hitCount;
    }

    public void setHitCount(long hitCount) {
        this.hitCount = hitCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
