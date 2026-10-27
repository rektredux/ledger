package com.ledger.recurring;

import com.ledger.common.TextSimilarity;
import com.ledger.money.Money;
import com.ledger.txn.CanonicalTransaction;
import com.ledger.txn.CanonicalTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Recurring expense definitions and the forecasts they generate.
 *
 * <h2>A forecast is not a transaction</h2>
 * This service never creates a {@link CanonicalTransaction}. It generates
 * {@link RecurringOccurrence} rows, which are expectations, and then tries to
 * match each one against spending that ingestion actually observed. An
 * occurrence counts as paid only when {@code matchedTransactionId} is set by
 * real evidence.
 *
 * <p>The alternative — materialising the rent payment because the first of the
 * month arrived — would mean the dashboard reports money the user may never
 * have paid, and would double-count as soon as the real payment was ingested.
 *
 * <h2>Idempotent generation</h2>
 * Generation is keyed on {@code (definition, dueDate)} with a unique index
 * behind it, so running it twice for the same month cannot produce a second
 * forecast.
 */
@Service
public class RecurringService {

    private static final Logger log = LoggerFactory.getLogger(RecurringService.class);

    /**
     * How long after a due date to keep waiting before calling it missed.
     *
     * <p>Generous because a payment can be a few days late, a bank alert can be
     * delayed, and a weekend pushes everything out. Marking something missed
     * too eagerly produces a false alarm; marking it late produces a slightly
     * stale "expected", which is the better failure.
     */
    private static final int GRACE_DAYS = 5;

    /** Window either side of the due date in which a payment is accepted. */
    private static final int MATCH_WINDOW_DAYS = 7;

    private final RecurringExpenseRepository definitions;
    private final RecurringOccurrenceRepository occurrences;
    private final CanonicalTransactionRepository transactions;

    public RecurringService(RecurringExpenseRepository definitions,
                            RecurringOccurrenceRepository occurrences,
                            CanonicalTransactionRepository transactions) {
        this.definitions = definitions;
        this.occurrences = occurrences;
        this.transactions = transactions;
    }

    /**
     * An upcoming or past expectation, for the UI.
     *
     * @param confirmed true only when real spending was matched to it
     */
    public record UpcomingPayment(String occurrenceId,
                                  String definitionId,
                                  String name,
                                  long amountMinor,
                                  String currency,
                                  LocalDate dueOn,
                                  String status,
                                  boolean confirmed,
                                  String matchedTransactionId,
                                  String categoryId,
                                  String accountId) {
    }

    /**
     * Generates forecasts for every active definition up to {@code horizon}.
     *
     * @return how many new occurrences were created
     */
    @Transactional
    public int generateThrough(LocalDate horizon) {
        int created = 0;
        for (RecurringExpense definition : definitions.findByActiveTrueOrderByNameAsc()) {
            LocalDate cursor = definition.getStartsOn();
            // Bounded iteration: a weekly definition over a long horizon is the
            // worst case, and the bound stops a malformed definition (day 0,
            // say) from looping forever.
            for (int i = 0; i < 520 && !cursor.isAfter(horizon); i++) {
                LocalDate due = definition.nextDueOnOrAfter(cursor);
                if (due.isAfter(horizon) || !definition.coversDate(due)) {
                    break;
                }
                if (!occurrences.existsByRecurringExpenseIdAndDueOn(definition.getId(), due)) {
                    occurrences.save(new RecurringOccurrence(definition.getId(), due));
                    created++;
                }
                cursor = due.plusDays(1);
            }
        }
        if (created > 0) {
            log.info("Generated {} recurring expectation(s)", created);
        }
        return created;
    }

    /**
     * Attempts to match outstanding expectations against observed spending.
     *
     * <p>Conservative by design: the amount must be close, the date within a
     * week, and the merchant must agree when the definition names one. An
     * expectation that cannot be matched stays {@code EXPECTED} and eventually
     * becomes {@code MISSED}, which is surfaced as a possible coverage gap
     * rather than quietly assumed paid.
     *
     * @return how many expectations were matched
     */
    @Transactional
    public int reconcileExpectations(LocalDate today) {
        int matched = 0;

        for (RecurringExpense definition : definitions.findAllByOrderByNameAsc()) {
            for (RecurringOccurrence occurrence
                    : occurrences.findByRecurringExpenseIdOrderByDueOnDesc(definition.getId())) {

                if (occurrence.getStatus() != RecurringOccurrence.Status.EXPECTED) {
                    continue;
                }
                if (occurrence.getDueOn().isAfter(today)) {
                    continue;
                }

                Optional<CanonicalTransaction> candidate = findPayment(definition, occurrence);
                if (candidate.isPresent()) {
                    occurrence.matchTo(candidate.get().getId());
                    occurrences.save(occurrence);
                    matched++;
                } else if (occurrence.getDueOn().plusDays(GRACE_DAYS).isBefore(today)) {
                    occurrence.setStatus(RecurringOccurrence.Status.MISSED);
                    occurrences.save(occurrence);
                }
            }
        }
        return matched;
    }

    /**
     * Finds spending that plausibly satisfies an expectation.
     *
     * <p>Only transactions not already claimed by another expectation are
     * considered, so one payment cannot satisfy two months' rent.
     */
    private Optional<CanonicalTransaction> findPayment(RecurringExpense definition,
                                                       RecurringOccurrence occurrence) {
        LocalDate from = occurrence.getDueOn().minusDays(MATCH_WINDOW_DAYS);
        LocalDate to = occurrence.getDueOn().plusDays(MATCH_WINDOW_DAYS);

        // A 10% tolerance absorbs a changed subscription price or a variable
        // utility bill without matching something unrelated.
        long tolerance = Math.max(100L, definition.getAmountMinor() / 10);

        List<CanonicalTransaction> candidates = transactions.findMatchCandidates(
                definition.getAmountMinor(), definition.getCurrency(), from, to,
                definition.getAccountId());

        for (CanonicalTransaction candidate : candidates) {
            if (!occurrences.findByMatchedTransactionId(candidate.getId()).isEmpty()) {
                continue;
            }
            if (Math.abs(candidate.getAmountMinor() - definition.getAmountMinor()) > tolerance) {
                continue;
            }
            if (definition.getMerchant() != null && candidate.getMerchant() != null) {
                double similarity = TextSimilarity.similarity(
                        definition.getMerchant(), candidate.getMerchant());
                if (similarity < 0.6) {
                    continue;
                }
            }
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Expectations due in a window, newest definitions resolved for display. */
    public List<UpcomingPayment> upcoming(LocalDate from, LocalDate to) {
        List<UpcomingPayment> result = new ArrayList<>();
        for (RecurringOccurrence occurrence : occurrences.findDueBetween(from, to)) {
            definitions.findById(occurrence.getRecurringExpenseId()).ifPresent(definition ->
                    result.add(new UpcomingPayment(
                            occurrence.getId(),
                            definition.getId(),
                            definition.getName(),
                            definition.getAmountMinor(),
                            definition.getCurrency(),
                            occurrence.getDueOn(),
                            occurrence.getStatus().name(),
                            occurrence.isConfirmed(),
                            occurrence.getMatchedTransactionId(),
                            definition.getCategoryId(),
                            definition.getAccountId())));
        }
        return result;
    }

    /** Expectations whose due date passed with nothing matched. */
    public List<UpcomingPayment> missed(LocalDate today) {
        List<UpcomingPayment> result = new ArrayList<>();
        for (RecurringOccurrence occurrence : occurrences.findOverdue(today.minusDays(GRACE_DAYS))) {
            definitions.findById(occurrence.getRecurringExpenseId()).ifPresent(definition ->
                    result.add(new UpcomingPayment(
                            occurrence.getId(), definition.getId(), definition.getName(),
                            definition.getAmountMinor(), definition.getCurrency(),
                            occurrence.getDueOn(), occurrence.getStatus().name(), false,
                            null, definition.getCategoryId(), definition.getAccountId())));
        }
        return result;
    }

    // --- Definition management ---------------------------------------------

    @Transactional
    public RecurringExpense create(String name, Money amount, RecurringExpense.Cadence cadence,
                                   int dayOfPeriod, LocalDate startsOn, String categoryId,
                                   String accountId, String merchant, Integer monthOfYear) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a recurring expense needs a name");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("a recurring expense needs a positive amount");
        }
        int maxDay = cadence == RecurringExpense.Cadence.WEEKLY ? 7 : 31;
        if (dayOfPeriod < 1 || dayOfPeriod > maxDay) {
            throw new IllegalArgumentException(
                    "day must be between 1 and " + maxDay + " for a " + cadence + " expense");
        }

        RecurringExpense definition = new RecurringExpense(
                name.trim(), amount, cadence, dayOfPeriod,
                startsOn == null ? LocalDate.now() : startsOn);
        definition.setCategoryId(categoryId);
        definition.setAccountId(accountId);
        definition.setMerchant(merchant);
        definition.setMonthOfYear(monthOfYear);
        return definitions.save(definition);
    }

    @Transactional
    public void deactivate(String id) {
        RecurringExpense definition = definitions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown recurring expense: " + id));
        definition.setActive(false);
        definitions.save(definition);
    }

    @Transactional
    public void delete(String id) {
        RecurringExpense definition = definitions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown recurring expense: " + id));
        // Occurrences cascade. The transactions they matched are untouched:
        // they are real spending and exist independently of the forecast.
        definitions.delete(definition);
    }

    /** Marks a single expectation as not going to happen. */
    @Transactional
    public RecurringOccurrence skip(String occurrenceId) {
        RecurringOccurrence occurrence = occurrences.findById(occurrenceId)
                .orElseThrow(() -> new IllegalArgumentException("unknown occurrence: " + occurrenceId));
        occurrence.setStatus(RecurringOccurrence.Status.SKIPPED);
        return occurrences.save(occurrence);
    }

    public List<RecurringExpense> findAll() {
        return definitions.findAllByOrderByNameAsc();
    }
}
