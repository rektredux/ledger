package com.ledger.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Empties the transactional tables between tests.
 *
 * <p>Reference data seeded by migration V2 (categories, built-in category rules
 * and merchant aliases) is preserved, because that data ships with the
 * application and every test should see it. Rules and aliases the test itself
 * created — anything not {@code BUILTIN} — are removed, so a test that
 * exercises rule learning cannot leak a rule into the next test.
 *
 * <p>Order matters: children before parents, because foreign keys are enforced.
 */
@Component
public class DatabaseCleaner {

    /**
     * Delete order, children first. Listed explicitly rather than discovered
     * from metadata so that adding a table is a deliberate decision about
     * whether tests should reset it.
     */
    private static final List<String> TABLES_IN_DELETE_ORDER = List.of(
            "match_decision",
            "review_item",
            "recurring_occurrence",
            "source_record",
            "sync_run",
            "import_batch",
            "canonical_transaction",
            "recurring_expense",
            "budget",
            "provider_connection",
            "account"
    );

    private final JdbcTemplate jdbc;

    public DatabaseCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void clean() {
        for (String table : TABLES_IN_DELETE_ORDER) {
            jdbc.update("delete from " + table);
        }
        // Keep the shipped rules, drop anything a test authored or learned.
        jdbc.update("delete from category_rule where origin <> 'BUILTIN'");
        jdbc.update("delete from merchant_alias where id not like 'ma-%'");
        // User-created categories use UUID ids; seeded ones use readable slugs.
        jdbc.update("delete from category where system = 0");
    }
}
