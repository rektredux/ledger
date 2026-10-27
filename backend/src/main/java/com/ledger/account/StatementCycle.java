package com.ledger.account;

import java.time.LocalDate;

/**
 * A credit card billing period, derived from the configured statement day.
 *
 * <p>Statement days of 29, 30 and 31 are clamped to the last day of short
 * months, so a card that statements on the 31st closes on 28 February rather
 * than skipping the month or throwing.
 *
 * @param from       first day of the period, inclusive
 * @param to         statement date, inclusive
 * @param dueOn      payment due date, or null when no due day is configured
 * @param isCurrent  true when this is the period in progress
 */
public record StatementCycle(LocalDate from, LocalDate to, LocalDate dueOn, boolean isCurrent) {

    /**
     * The statement period containing {@code reference}.
     *
     * <p>The period ends on the statement day falling on or after
     * {@code reference} and begins the day after the previous statement day.
     * A card with a statement day of 15 therefore has its March period running
     * 16 February to 15 March.
     *
     * @return null when the account has no statement day configured, because a
     *     guessed billing cycle would produce a confidently wrong "spending
     *     this statement period" figure
     */
    public static StatementCycle containing(Account card, LocalDate reference, LocalDate today) {
        Integer statementDay = card.getStatementDay();
        if (statementDay == null) {
            return null;
        }

        LocalDate closeThisMonth = clampDay(reference, statementDay);
        LocalDate close = reference.isAfter(closeThisMonth)
                ? clampDay(reference.plusMonths(1), statementDay)
                : closeThisMonth;
        LocalDate previousClose = clampDay(close.minusMonths(1), statementDay);
        LocalDate start = previousClose.plusDays(1);

        LocalDate due = null;
        if (card.getDueDay() != null) {
            // The due date is in the month following the statement close when
            // the due day falls earlier in the month than the statement day,
            // which is the usual arrangement.
            LocalDate sameMonthDue = clampDay(close, card.getDueDay());
            due = sameMonthDue.isAfter(close) ? sameMonthDue : clampDay(close.plusMonths(1), card.getDueDay());
        }

        boolean current = !today.isBefore(start) && !today.isAfter(close);
        return new StatementCycle(start, close, due, current);
    }

    /** The period in progress today. */
    public static StatementCycle current(Account card, LocalDate today) {
        return containing(card, today, today);
    }

    /** The most recently closed period. */
    public static StatementCycle previous(Account card, LocalDate today) {
        StatementCycle currentCycle = current(card, today);
        if (currentCycle == null) {
            return null;
        }
        return containing(card, currentCycle.from().minusDays(1), today);
    }

    private static LocalDate clampDay(LocalDate month, int day) {
        LocalDate first = month.withDayOfMonth(1);
        return first.withDayOfMonth(Math.clamp(day, 1, first.lengthOfMonth()));
    }

    /** True when {@code date} falls inside this period. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }
}
