package com.ledger.category;

import com.ledger.common.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * A spending or income category, optionally nested one level under a parent
 * (the "subcategory" of the requirements).
 *
 * <p>The parent link is a plain id rather than a JPA association. The tree is
 * two levels deep and is always loaded whole and cached by
 * {@code CategoryService}, so an association would buy lazy loading the
 * application never wants and add N+1 risk to every transaction listing.
 */
@Entity
@Table(name = "category")
public class Category {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @NotBlank
    @Column(name = "name", nullable = false)
    private String name;

    /** Null for a top-level category. */
    @Column(name = "parent_id")
    private String parentId;

    /**
     * True for categories that represent money moving rather than being spent:
     * transfers between owned accounts, credit card bill payments, cash
     * withdrawals and money moved into investments.
     *
     * <p>Spending reports exclude these. Without the flag, moving ₹50,000 from
     * savings to a fixed deposit would appear as the user's biggest expense of
     * the month.
     */
    @Column(name = "excluded_from_spending", nullable = false)
    private boolean excludedFromSpending;

    /** True for seeded categories. These cannot be deleted, only hidden. */
    @Column(name = "system", nullable = false)
    private boolean system;

    /** Lucide icon name, for the UI. */
    @Column(name = "icon")
    private String icon;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Category() {
        // for JPA
    }

    public Category(String name, String parentId) {
        this.name = name;
        this.parentId = parentId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isTopLevel() {
        return parentId == null;
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public boolean isExcludedFromSpending() {
        return excludedFromSpending;
    }

    public void setExcludedFromSpending(boolean excludedFromSpending) {
        this.excludedFromSpending = excludedFromSpending;
    }

    public boolean isSystem() {
        return system;
    }

    public void setSystem(boolean system) {
        this.system = system;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
