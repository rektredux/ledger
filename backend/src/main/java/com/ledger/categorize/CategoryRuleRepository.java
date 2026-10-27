package com.ledger.categorize;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, String> {

    /**
     * All enabled rules, highest priority first.
     *
     * <p>The whole set is loaded and cached by {@code CategorizationEngine}
     * rather than queried per transaction: there are tens of rules, not
     * thousands, and evaluating them in memory keeps a bulk import from issuing
     * one query per row.
     */
    List<CategoryRule> findByEnabledTrueOrderByPriorityDesc();

    List<CategoryRule> findAllByOrderByPriorityDescCreatedAtAsc();

    Optional<CategoryRule> findByPatternAndMatchTypeAndOrigin(
            String pattern, MatchType matchType, CategoryRule.Origin origin);

    List<CategoryRule> findByCategoryId(String categoryId);

    List<CategoryRule> findByOrigin(CategoryRule.Origin origin);

    long countByOrigin(CategoryRule.Origin origin);

    /** Rules that have never fired, surfaced in Settings as likely dead weight. */
    @Query("""
            select r from CategoryRule r
            where r.hitCount = 0 and r.origin <> :builtin
            order by r.createdAt asc
            """)
    List<CategoryRule> findUnusedUserRules(@Param("builtin") CategoryRule.Origin builtin);

    default List<CategoryRule> findUnusedUserRules() {
        return findUnusedUserRules(CategoryRule.Origin.BUILTIN);
    }
}
