package com.ledger.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Base class for tests that need a real Spring context and a real database.
 *
 * <p>These tests run against SQLite, not an in-memory substitute like H2. The
 * application's correctness depends on SQLite-specific behaviour in several
 * places — ISO-8601 text date comparison, {@code substr()} in the monthly
 * trend query, partial unique indexes for idempotency, per-connection foreign
 * key enforcement — and an H2 test would verify none of it. A temporary file
 * costs a few milliseconds and tests what actually ships.
 *
 * <p>Because {@code spring.jpa.hibernate.ddl-auto=validate} is set, merely
 * starting this context proves the JPA entities and the Flyway-migrated schema
 * agree. A mismatched column name fails the whole suite immediately rather than
 * surfacing as a runtime error during a sync months later.
 *
 * <p>All subclasses share one context and one database file (the property
 * values are identical, so Spring's context cache reuses it). Isolation comes
 * from {@link DatabaseCleaner}, which empties the transactional tables before
 * each test while leaving the seeded reference data from migration V2 in place.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    /**
     * A fixed location under {@code target/} rather than a system temp
     * directory. Two reasons: the JVM's default temp directory is outside the
     * project and is not writable in some sandboxed environments, and a
     * predictable path means a failing test leaves behind a database that can
     * be opened with {@code sqlite3} to see what actually happened.
     *
     * <p>Deleted on first access in each JVM so a schema change in a migration
     * cannot be masked by a database left over from a previous run.
     */
    private static final Path DB_FILE = resolveAndResetDatabase();

    private static Path resolveAndResetDatabase() {
        try {
            Path dir = Path.of("target", "test-db");
            Files.createDirectories(dir);
            Path db = dir.resolve("ledger-test.db");
            // SQLite keeps -wal and -shm sidecars; all three must go together.
            for (String suffix : new String[] {"", "-wal", "-shm"}) {
                Files.deleteIfExists(Path.of(db + suffix));
            }
            return db.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("could not prepare the test database directory", e);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + DB_FILE + "?foreign_keys=on&busy_timeout=10000");
        // The scheduler must not fire during tests: a background sync would
        // race with the assertions. Tests drive sync explicitly instead.
        registry.add("ledger.sync.enabled", () -> "false");
        // Deterministic key so token encryption round-trips without touching
        // the developer's real key file.
        // Exactly 32 bytes (256 bits), base64-encoded. Required by TokenCipher.
        registry.add("ledger.security.token-key", () -> "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=");
    }

    @Autowired
    protected DatabaseCleaner databaseCleaner;

    @BeforeEach
    void resetDatabase() {
        databaseCleaner.clean();
    }
}
