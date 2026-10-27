package com.ledger.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;

/**
 * An inclusive date range, with the common presets.
 *
 * <p>Both ends are inclusive, which matches the SQL {@code BETWEEN} the
 * repository uses and avoids the off-by-one that an exclusive end invites. The
 * month boundary tests in {@code AccountingRulesTest} pin this down.
 */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("both ends of a date range are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("range start " + from + " is after its end " + to);
        }
    }

    public static DateRange of(LocalDate from, LocalDate to) {
        return new DateRange(from, to);
    }

    public static DateRange monthOf(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        return new DateRange(month.atDay(1), month.atEndOfMonth());
    }

    public static DateRange month(YearMonth month) {
        return new DateRange(month.atDay(1), month.atEndOfMonth());
    }

    /** The calendar month before this range's start. */
    public DateRange previousMonth() {
        YearMonth previous = YearMonth.from(from).minusMonths(1);
        return month(previous);
    }

    public static DateRange thisMonth(LocalDate today) {
        return monthOf(today);
    }

    public static DateRange lastMonth(LocalDate today) {
        return month(YearMonth.from(today).minusMonths(1));
    }

    public static DateRange last7Days(LocalDate today) {
        return new DateRange(today.minusDays(6), today);
    }

    public static DateRange last30Days(LocalDate today) {
        return new DateRange(today.minusDays(29), today);
    }

    public static DateRange last90Days(LocalDate today) {
        return new DateRange(today.minusDays(89), today);
    }

    public static DateRange thisYear(LocalDate today) {
        return new DateRange(today.with(TemporalAdjusters.firstDayOfYear()),
                today.with(TemporalAdjusters.lastDayOfYear()));
    }

    /**
     * The last {@code months} whole calendar months, ending with the month
     * containing {@code today}. Used for the trend chart.
     */
    public static DateRange trailingMonths(LocalDate today, int months) {
        YearMonth end = YearMonth.from(today);
        YearMonth start = end.minusMonths(Math.max(0, months - 1));
        return new DateRange(start.atDay(1), end.atEndOfMonth());
    }

    /**
     * The immediately preceding range of the same length, for period-on-period
     * comparison of non-month ranges.
     */
    public DateRange precedingEquivalent() {
        long days = lengthInDays();
        return new DateRange(from.minusDays(days), from.minusDays(1));
    }

    public long lengthInDays() {
        return to.toEpochDay() - from.toEpochDay() + 1;
    }

    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    /** True when this range is exactly one calendar month. */
    public boolean isWholeMonth() {
        YearMonth month = YearMonth.from(from);
        return from.equals(month.atDay(1)) && to.equals(month.atEndOfMonth());
    }

    /** {@code yyyy-MM} when the range is a whole month, else null. */
    public String asPeriod() {
        return isWholeMonth() ? YearMonth.from(from).toString() : null;
    }

    @Override
    public String toString() {
        return from + " to " + to;
    }
}
