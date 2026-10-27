package com.ledger.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, String> {

    /**
     * Budgets in force for a period: those scoped to exactly that month, plus
     * the recurring defaults (null period). {@code BudgetService} prefers the
     * month-specific row when both exist.
     */
    @Query("select b from Budget b where b.period = :period or b.period is null")
    List<Budget> findApplicable(@Param("period") String period);

    Optional<Budget> findByCategoryIdAndPeriod(String categoryId, String period);

    /** The overall (all-category) budget for a specific month. */
    @Query("select b from Budget b where b.categoryId is null and b.period = :period")
    Optional<Budget> findOverallForPeriod(@Param("period") String period);

    /** The overall recurring default budget. */
    @Query("select b from Budget b where b.categoryId is null and b.period is null")
    Optional<Budget> findOverallDefault();

    List<Budget> findByPeriodIsNull();
}
