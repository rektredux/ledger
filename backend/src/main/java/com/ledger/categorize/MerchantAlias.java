package com.ledger.categorize;

import com.ledger.common.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Maps a messy merchant string from a payment rail onto a clean display name.
 *
 * <p>Bank alerts rarely contain a tidy merchant name. The same coffee shop
 * arrives as {@code "UPI/STARBUCKS COFFEE/9876"}, {@code "POS 4567 STARBUCKS
 * IN"} and {@code "STARBUCK*INDIA BLR"}. Without normalisation each variant
 * becomes its own entry in merchant-wise spending and each needs its own
 * category rule.
 *
 * <p>Applied by {@code MerchantNormalizer} after it has stripped rail prefixes
 * and reference numbers, and before category rules are evaluated.
 */
@Entity
@Table(name = "merchant_alias")
public class MerchantAlias {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    /** Lower-cased pattern matched against the cleaned merchant string. */
    @Column(name = "pattern", nullable = false)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false)
    private MatchType matchType = MatchType.CONTAINS;

    /** The display name to use when the pattern matches. */
    @Column(name = "canonical_name", nullable = false)
    private String canonicalName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MerchantAlias() {
        // for JPA
    }

    public MerchantAlias(String pattern, MatchType matchType, String canonicalName) {
        this.pattern = pattern;
        this.matchType = matchType;
        this.canonicalName = canonicalName;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean matches(String value) {
        return matchType.matches(pattern, value);
    }

    /** Longer, more specific patterns are applied first. */
    public int rank() {
        return matchType.specificityBonus() + (pattern == null ? 0 : pattern.length());
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

    public String getCanonicalName() {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
