package com.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
@EnableScheduling
public class LedgerApplication {

    private static final Logger log = LoggerFactory.getLogger(LedgerApplication.class);

    public static void main(String[] args) {
        ensureDatabaseDirectory();
        SpringApplication.run(LedgerApplication.class, args);
    }

    /**
     * Creates the directory that will hold the SQLite file.
     *
     * <p>SQLite creates a missing database file but not a missing parent
     * directory, so a first run with the default {@code ./data/ledger.db} would
     * otherwise fail inside Flyway with an opaque "unable to open database
     * file". Done here, before the context starts, because the DataSource is
     * built very early and ordering a bean before it is fragile.
     */
    private static void ensureDatabaseDirectory() {
        String configured = System.getenv("LEDGER_DB_PATH");
        Path dbPath = Paths.get(configured == null || configured.isBlank() ? "./data/ledger.db" : configured);
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (Exception e) {
            // Let startup continue: the DataSource will fail with its own, more
            // specific error if the directory really is unusable.
            log.warn("Could not create database directory {}: {}", parent, e.getMessage());
        }
    }
}
