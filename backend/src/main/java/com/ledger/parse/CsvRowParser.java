package com.ledger.parse;

import com.ledger.ingest.RawObservation;
import com.ledger.money.Money;
import com.ledger.txn.TransactionStatus;
import com.ledger.txn.TransactionType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the synthetic observation that {@code CsvService} builds for each
 * imported row.
 *
 * <p>CSV rows could have been inserted straight into the ledger, bypassing all
 * of this. Routing them through the same ingestion pipeline instead buys three
 * things that matter more than the small amount of indirection: imported rows
 * are deduplicated against transactions already collected from Gmail, they
 * leave the same source-record evidence trail, and they go through the same
 * categorisation. Importing a bank statement for a month already covered by
 * email alerts therefore links the rows to the existing transactions instead of
 * doubling the month.
 *
 * <p>Runs at the highest priority so it never competes with the email parsers —
 * and its format is unambiguous, so it cannot false-positive on real mail.
 */
@Component
public class CsvRowParser implements MessageParser {

    /** First line of the synthetic content, used as the format marker. */
    private static final String MARKER = "LEDGER-CSV-ROW";

    @Override
    public String id() {
        return "csv-row";
    }

    @Override
    public String displayName() {
        return "CSV import rows";
    }

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public boolean canParse(RawObservation observation) {
        String content = observation.content();
        return content != null && content.startsWith(MARKER);
    }

    @Override
    public Optional<ParseResult> parse(RawObservation observation) {
        if (!canParse(observation)) {
            return Optional.empty();
        }
        Map<String, String> fields = readFields(observation.content());

        Long amountMinor = parseLong(fields.get("amount_minor"));
        LocalDate date = parseDate(fields.get("date"));
        TransactionType type = parseType(fields.get("type"));

        // The row was already validated at preview time, so anything missing
        // here is a bug rather than bad user input. Returning an incomplete
        // result routes it to the exception queue where it is visible, instead
        // of throwing and failing the whole import.
        if (amountMinor == null || date == null || type == null) {
            return Optional.of(ParseResult.builder()
                    .amount(amountMinor == null ? null : Money.ofMinor(amountMinor, currency(fields)))
                    .occurredOn(date)
                    .type(type)
                    .confidence(0.0)
                    .build());
        }

        return Optional.of(ParseResult.builder()
                .amount(Money.ofMinor(amountMinor, currency(fields)))
                .occurredOn(date)
                .type(type)
                .merchantRaw(blankToNull(fields.get("merchant")))
                .reference(blankToNull(fields.get("reference")))
                .instrumentHint(null)
                .status(TransactionStatus.POSTED)
                .description(blankToNull(fields.get("notes")))
                // The user stated these values explicitly, so extraction is
                // certain. Whether they are *correct* is their responsibility,
                // which is why the import preview shows them first.
                .confidence(1.0)
                .build());
    }

    private static String currency(Map<String, String> fields) {
        String currency = blankToNull(fields.get("currency"));
        return currency == null ? Money.INR : currency;
    }

    private static Map<String, String> readFields(String content) {
        Map<String, String> fields = new HashMap<>();
        for (String line : content.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return fields;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static TransactionType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TransactionType.parse(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
