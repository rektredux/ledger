package com.ledger.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, String> {

    List<Category> findAllByOrderByNameAsc();

    List<Category> findByParentId(String parentId);

    Optional<Category> findByNameIgnoreCaseAndParentId(String name, String parentId);

    /** Categories that must never be counted as spending (transfers, investments). */
    List<Category> findByExcludedFromSpendingTrue();
}
