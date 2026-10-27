package com.ledger.recurring;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RecurringOccurrenceRepository extends JpaRepository<RecurringOccurrence, String> {

    /**
     * The idempotency guard for generation. Backed by the unique index on
     * {@code (recurring_expense_id, due_on)}, so regenerating a month cannot
     * create a second occurrence even if two generators race.
     */
    Optional<RecurringOccurrence> findByRecurringExpenseIdAndDueOn(String recurringExpenseId, LocalDate dueOn);

    boolean existsByRecurringExpenseIdAndDueOn(String recurringExpenseId, LocalDate dueOn);

    List<RecurringOccurrence> findByRecurringExpenseIdOrderByDueOnDesc(String recurringExpenseId);

    /** Forecasts due in a window, for the "upcoming payments" list. */
    @Query("""
            select o from RecurringOccurrence o
            where o.dueOn between :from and :to
            order by o.dueOn asc
            """)
    List<RecurringOccurrence> findDueBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Expectations whose due date has passed with no matching transaction.
     * Either the payment did not happen, or it did and no source observed it.
     * Both are worth telling the user about rather than assuming either way.
     */
    @Query("""
            select o from RecurringOccurrence o
            where o.status = :expected and o.dueOn < :cutoff
            order by o.dueOn asc
            """)
    List<RecurringOccurrence> findOverdue(@Param("cutoff") LocalDate cutoff,
                                          @Param("expected") RecurringOccurrence.Status expected);

    default List<RecurringOccurrence> findOverdue(LocalDate cutoff) {
        return findOverdue(cutoff, RecurringOccurrence.Status.EXPECTED);
    }

    List<RecurringOccurrence> findByMatchedTransactionId(String matchedTransactionId);
}
