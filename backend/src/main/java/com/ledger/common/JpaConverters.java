package com.ledger.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Temporal attribute converters.
 *
 * <p>SQLite has no date or timestamp type: a "DATE" column has no storage class
 * of its own and what lands in it depends entirely on the driver. Rather than
 * depend on that behaviour, every temporal value in this application is stored
 * as fixed-width ISO-8601 text. Two consequences make this worth the small
 * amount of ceremony:
 *
 * <ul>
 *   <li>Fixed-width ISO-8601 sorts and range-compares correctly as text, so
 *       {@code WHERE occurred_on BETWEEN ? AND ?} is exact rather than
 *       approximate. Month boundaries in reports depend on this.</li>
 *   <li>A JSON backup or a {@code sqlite3} dump is human-readable and
 *       unambiguous, which matters for a file the user is told to back up
 *       themselves.</li>
 * </ul>
 *
 * <p>Instants are normalised to UTC before formatting, with exactly three
 * fractional digits so that width stays constant and ordering stays correct.
 *
 * <p>These converters are {@code autoApply = true}, so entity fields just
 * declare {@code LocalDate} / {@code Instant} / {@code LocalTime}. On a future
 * move to PostgreSQL these columns become real {@code DATE} / {@code
 * TIMESTAMPTZ} / {@code TIME} and these three classes are simply deleted.
 */
public final class JpaConverters {

    private JpaConverters() {
    }

    /** {@code yyyy-MM-dd}. */
    static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** {@code HH:mm:ss}. */
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} in UTC. Always three fractional
     * digits: {@code ISO_INSTANT} omits them when zero, which would break
     * lexicographic ordering between ".000Z" and no-fraction values.
     */
    static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Converter(autoApply = true)
    public static class LocalDateConverter implements AttributeConverter<LocalDate, String> {
        @Override
        public String convertToDatabaseColumn(LocalDate value) {
            return value == null ? null : DATE.format(value);
        }

        @Override
        public LocalDate convertToEntityAttribute(String value) {
            return value == null || value.isBlank() ? null : LocalDate.parse(value.trim(), DATE);
        }
    }

    @Converter(autoApply = true)
    public static class InstantConverter implements AttributeConverter<Instant, String> {
        @Override
        public String convertToDatabaseColumn(Instant value) {
            return value == null ? null : INSTANT.format(value);
        }

        @Override
        public Instant convertToEntityAttribute(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            // Accept any valid ISO-8601 instant on read, not just our own output
            // format, so hand-edited data and restored backups from other tools
            // still load.
            return Instant.parse(normaliseInstant(value.trim()));
        }

        private static String normaliseInstant(String raw) {
            // "2024-01-01T00:00:00" (no zone) is treated as UTC rather than
            // rejected; this shows up in hand-written fixtures.
            if (!raw.endsWith("Z") && !raw.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                return raw + "Z";
            }
            return raw;
        }
    }

    @Converter(autoApply = true)
    public static class LocalTimeConverter implements AttributeConverter<LocalTime, String> {
        @Override
        public String convertToDatabaseColumn(LocalTime value) {
            return value == null ? null : TIME.format(value);
        }

        @Override
        public LocalTime convertToEntityAttribute(String value) {
            return value == null || value.isBlank() ? null : LocalTime.parse(value.trim(), TIME);
        }
    }
}
