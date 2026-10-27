package com.ledger.category;

import com.ledger.common.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read and write access to the category tree.
 *
 * <p>The tree is small (tens of rows, two levels) and is read on essentially
 * every transaction operation, so lookups go through an in-memory snapshot
 * rather than the database. The snapshot is rebuilt on any mutation. Without
 * it, listing a page of transactions would issue one category query per row.
 */
@Service
public class CategoryService {

    /** The category assigned when nothing else is known. Always present. */
    public static final String UNCATEGORIZED_ID = "uncategorized";
    /** Credit card bill payments. Excluded from spending by definition. */
    public static final String CARD_PAYMENT_CATEGORY_ID = "transfer.cardbill";
    /** Transfers between the user's own accounts. */
    public static final String SELF_TRANSFER_CATEGORY_ID = "transfer.self";

    private final CategoryRepository repository;

    /** Rebuilt wholesale on mutation; volatile so readers see a complete map. */
    private volatile Snapshot snapshot;

    public CategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    private record Snapshot(Map<String, Category> byId, Set<String> excludedFromSpending) {
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            current = load();
        }
        return current;
    }

    private synchronized Snapshot load() {
        List<Category> all = repository.findAll();
        Map<String, Category> byId = all.stream()
                .collect(Collectors.toMap(Category::getId, c -> c, (a, b) -> a, LinkedHashMap::new));

        // A subcategory inherits exclusion from its parent: marking
        // "Transfers" non-spending must cover "Credit Card Payment" beneath it,
        // even if that child's own flag was never set.
        Set<String> excluded = all.stream()
                .filter(c -> isExcluded(c, byId))
                .map(Category::getId)
                .collect(Collectors.toSet());

        Snapshot fresh = new Snapshot(Map.copyOf(byId), Set.copyOf(excluded));
        this.snapshot = fresh;
        return fresh;
    }

    private static boolean isExcluded(Category category, Map<String, Category> byId) {
        Category cursor = category;
        // Depth is 2 by design; the bound stops a cycle from hanging startup if
        // data is ever corrupted by a bad restore.
        for (int depth = 0; cursor != null && depth < 8; depth++) {
            if (cursor.isExcludedFromSpending()) {
                return true;
            }
            cursor = cursor.getParentId() == null ? null : byId.get(cursor.getParentId());
        }
        return false;
    }

    /** Invalidates the cache. Call after any write outside this service. */
    public void invalidate() {
        this.snapshot = null;
    }

    // --- Lookups ------------------------------------------------------------

    public Optional<Category> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(snapshot().byId().get(id));
    }

    /**
     * Whether a category must be left out of spending totals, including by
     * inheritance from its parent. An unknown or null id is <em>not</em>
     * excluded: an uncategorized expense is still real spending.
     */
    public boolean isExcludedFromSpending(String categoryId) {
        return categoryId != null && snapshot().excludedFromSpending().contains(categoryId);
    }

    public Set<String> excludedFromSpendingIds() {
        return snapshot().excludedFromSpending();
    }

    /** Display name, qualified with its parent: {@code "Food & Dining / Groceries"}. */
    public String displayPath(String categoryId) {
        Category category = snapshot().byId().get(categoryId);
        if (category == null) {
            return "Uncategorized";
        }
        Category parent = category.getParentId() == null ? null : snapshot().byId().get(category.getParentId());
        return parent == null ? category.getName() : parent.getName() + " / " + category.getName();
    }

    /** Short display name without the parent prefix. */
    public String displayName(String categoryId) {
        Category category = snapshot().byId().get(categoryId);
        return category == null ? "Uncategorized" : category.getName();
    }

    /**
     * The top-level ancestor of a category, used to roll subcategory spending
     * up into the headline category chart.
     */
    public String rootIdOf(String categoryId) {
        Category category = snapshot().byId().get(categoryId);
        if (category == null) {
            return null;
        }
        Category cursor = category;
        for (int depth = 0; depth < 8; depth++) {
            if (cursor.getParentId() == null) {
                return cursor.getId();
            }
            Category parent = snapshot().byId().get(cursor.getParentId());
            if (parent == null) {
                return cursor.getId();
            }
            cursor = parent;
        }
        return cursor.getId();
    }

    public List<Category> findAll() {
        return new ArrayList<>(snapshot().byId().values());
    }

    /** Top-level categories, each with its children, ordered for display. */
    public List<CategoryTree> tree() {
        Map<String, Category> byId = snapshot().byId();
        List<Category> roots = byId.values().stream()
                .filter(Category::isTopLevel)
                .sorted(Comparator.comparing(Category::getName))
                .toList();
        List<CategoryTree> result = new ArrayList<>(roots.size());
        for (Category root : roots) {
            List<Category> children = byId.values().stream()
                    .filter(c -> root.getId().equals(c.getParentId()))
                    .sorted(Comparator.comparing(Category::getName))
                    .toList();
            result.add(new CategoryTree(root, children));
        }
        return result;
    }

    public record CategoryTree(Category category, List<Category> children) {
    }

    // --- Mutations ----------------------------------------------------------

    @Transactional
    public Category create(String name, String parentId, boolean excludedFromSpending) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("category name is required");
        }
        String trimmed = name.trim();
        repository.findByNameIgnoreCaseAndParentId(trimmed, parentId).ifPresent(existing -> {
            throw new IllegalArgumentException("a category named '" + trimmed + "' already exists here");
        });
        if (parentId != null) {
            Category parent = repository.findById(parentId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown parent category: " + parentId));
            if (parent.getParentId() != null) {
                // Reports and the budget UI assume two levels. Allowing deeper
                // nesting would silently break the roll-up in rootIdOf.
                throw new IllegalArgumentException("categories may only be nested one level deep");
            }
        }
        Category category = new Category(trimmed, parentId);
        category.setId(Ids.newId());
        category.setExcludedFromSpending(excludedFromSpending);
        category.setSystem(false);
        Category saved = repository.save(category);
        invalidate();
        return saved;
    }

    @Transactional
    public Category rename(String id, String name) {
        Category category = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown category: " + id));
        category.setName(name.trim());
        Category saved = repository.save(category);
        invalidate();
        return saved;
    }

    /**
     * Deletes a user-created category.
     *
     * <p>Seeded categories cannot be deleted: built-in rules and the accounting
     * logic reference them by id, so removing one would break both. Transactions
     * referencing the deleted category fall back to null via
     * {@code ON DELETE SET NULL} and surface as uncategorized rather than
     * vanishing.
     */
    @Transactional
    public void delete(String id) {
        Category category = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown category: " + id));
        if (category.isSystem()) {
            throw new IllegalArgumentException(
                    "built-in categories cannot be deleted; they are referenced by the accounting rules");
        }
        repository.delete(category);
        invalidate();
    }
}
