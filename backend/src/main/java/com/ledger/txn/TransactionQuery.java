package com.ledger.txn;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Filter criteria for listing transactions, as a JPA {@link Specification}.
 *
 * <p>Built as a specification rather than a hand-written query so the
 * Transactions screen's filters compose freely — any combination of date range,
 * account, category, type, status and free-text search — without a
 * combinatorial explosion of repository methods.
 *
 * <p>Every field is optional; a null means "do not constrain on this". The
 * drill-down links on the dashboard are just this query with one or two fields
 * set, which is what makes "click a category slice, see those transactions"
 * work without special-case endpoints.
 *
 * @param from         inclusive start date
 * @param to           inclusive end date
 * @param accountId    the primary or counter account
 * @param categoryId   the category, or any of its children
 * @param types        transaction types to include
 * @param statuses     settlement states to include
 * @param search       case-insensitive substring of merchant, description or notes
 * @param uncategorizedOnly only transactions with no confident category
 * @param unattributedOnly  only transactions with no account
 * @param needsReviewOnly   only transactions flagged for review
 * @param minAmountMinor    inclusive lower bound on the magnitude
 * @param maxAmountMinor    inclusive upper bound on the magnitude
 */
public record TransactionQuery(LocalDate from,
                               LocalDate to,
                               String accountId,
                               String categoryId,
                               List<TransactionType> types,
                               List<TransactionStatus> statuses,
                               String search,
                               Boolean uncategorizedOnly,
                               Boolean unattributedOnly,
                               Boolean needsReviewOnly,
                               Long minAmountMinor,
                               Long maxAmountMinor) {

    /**
     * Builds the specification.
     *
     * @param childCategoryIds ids of the filtered category's children, resolved
     *     by the caller from {@code CategoryService}. Passed in rather than
     *     looked up here so this class stays free of dependencies and remains a
     *     plain value object: a budget on "Food & Dining" must also match
     *     spending filed under "Groceries".
     */
    public Specification<CanonicalTransaction> toSpecification(List<String> childCategoryIds) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("occurredOn"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("occurredOn"), to));
            }

            if (accountId != null && !accountId.isBlank()) {
                // Either side of a transfer counts as "this account's"
                // transactions, which is what a user looking at an account
                // statement expects to see.
                predicates.add(builder.or(
                        builder.equal(root.get("accountId"), accountId),
                        builder.equal(root.get("counterAccountId"), accountId)));
            }

            if (categoryId != null && !categoryId.isBlank()) {
                List<String> ids = new ArrayList<>();
                ids.add(categoryId);
                if (childCategoryIds != null) {
                    ids.addAll(childCategoryIds);
                }
                predicates.add(root.get("categoryId").in(ids));
            }

            if (types != null && !types.isEmpty()) {
                predicates.add(root.get("type").in(types));
            }

            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            } else {
                // Void transactions carry no financial effect, so they are
                // hidden unless explicitly asked for.
                predicates.add(builder.notEqual(root.get("status"), TransactionStatus.VOID));
            }

            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("merchant")), pattern),
                        builder.like(builder.lower(root.get("merchantRaw")), pattern),
                        builder.like(builder.lower(root.get("description")), pattern),
                        builder.like(builder.lower(root.get("notes")), pattern),
                        builder.like(builder.lower(root.get("externalReference")), pattern)));
            }

            if (Boolean.TRUE.equals(uncategorizedOnly)) {
                predicates.add(builder.or(
                        builder.isNull(root.get("categoryId")),
                        builder.equal(root.get("categorySource"), CategorySource.UNCATEGORIZED)));
            }

            if (Boolean.TRUE.equals(unattributedOnly)) {
                predicates.add(builder.isNull(root.get("accountId")));
            }

            if (Boolean.TRUE.equals(needsReviewOnly)) {
                predicates.add(builder.equal(
                        root.get("reconciliationStatus"), ReconciliationStatus.NEEDS_REVIEW));
            }

            if (minAmountMinor != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("amountMinor"), minAmountMinor));
            }
            if (maxAmountMinor != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("amountMinor"), maxAmountMinor));
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** An unconstrained query. */
    public static TransactionQuery all() {
        return new TransactionQuery(null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
