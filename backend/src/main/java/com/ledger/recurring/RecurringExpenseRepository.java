package com.ledger.recurring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecurringExpenseRepository extends JpaRepository<RecurringExpense, String> {

    List<RecurringExpense> findByActiveTrueOrderByNameAsc();

    List<RecurringExpense> findAllByOrderByNameAsc();
}
