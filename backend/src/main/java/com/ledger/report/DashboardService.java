package com.ledger.report;

import com.ledger.account.Account;
import com.ledger.account.AccountService;
import com.ledger.category.CategoryService;
import com.ledger.money.Money;
import com.ledger.review.ReviewItem;
import com.ledger.review.ReviewService;
import com.ledger.source.ProviderConnection;
import com.ledger.source.ProviderConnectionRepository;
import com.ledger.source.SourceRecordRepository;
import com.ledger.source.SourceRecordStatus;
import com.ledger.sync.SyncRun;
import com.ledger.sync.SyncRunRepository;
import com.ledger.txn.CanonicalTransaction;
import com.ledger.txn.CanonicalTransactionRepository;
import com.ledger.txn.LedgerMath;
import com.ledger.txn.TransactionOrigin;
import com.ledger.txn.TransactionStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles the dashboard from real database records.
 *
 * <h2>Honesty is a feature here</h2>
 * Every figure this service returns is a correct sum of what the ledger
 * contains. That is not the same as a correct sum of what the user actually
 * spent, and the difference is the whole problem with automated expense
 * tracking: cash leaves no trace, an unsupported bank sends no parseable mail,
 * a message can fail to parse.
 *
 * <p>So alongside the numbers, every response carries a {@link Coverage} block:
 * which sources are connected, when they last succeeded, how much of the ledger
 * was collected automatically versus entered by hand, and how many records are
 * sitting unresolved. The UI renders these next to the totals rather than in a
 * settings page nobody opens. A number without that context would invite the
 * user to believe their spending total is complete, and it is not something
 * this application can promise.
 */
@Service
public class DashboardService {

    private final CanonicalTransactionRepository transactions;
    private final SourceRecordRepository sourceRecords;
    private final ProviderConnectionRepository connections;
    private final SyncRunRepository syncRuns;
    private final AccountService accountService;
    private final CategoryService categoryService;
    private final ReviewService reviewService;
    private final int maxAttempts;

    public DashboardService(CanonicalTransactionRepository transactions,
                            SourceRecordRepository sourceRecords,
                            ProviderConnectionRepository connections,
                            SyncRunRepository syncRuns,
                            AccountService accountService,
                            CategoryService categoryService,
                            ReviewService reviewService,
                            @Value("${ledger.sync.max-attempts:5}") int maxAttempts) {
        this.transactions = transactions;
        this.sourceRecords = sourceRecords;
        this.connections = connections;
        this.syncRuns = syncRuns;
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.reviewService = reviewService;
        this.maxAttempts = maxAttempts;
    }

    // --- Response shapes ----------------------------------------------------

    /** Headline totals for a period, with the previous period for comparison. */
    public record Totals(long grossSpendingMinor,
                         long spendingOffsetsMinor,
                         long netSpendingMinor,
                         long incomeMinor,
                         long netCashFlowMinor,
                         String currency,
                         long previousNetSpendingMinor,
                         long previousIncomeMinor,
                         Integer spendingChangePercent,
                         long transactionCount) {
    }

    public record CategorySlice(String categoryId,
                                String name,
                                String parentName,
                                long totalMinor,
                                long transactionCount,
                                int sharePercent) {
    }

    public record MerchantSlice(String merchant, long totalMinor, long transactionCount) {
    }

    public record PaymentMethodSlice(String accountId,
                                     String accountName,
                                     String accountType,
                                     long totalMinor,
                                     long transactionCount) {
    }

    public record MonthPoint(String period, long spendingMinor, long incomeMinor, long netSpendingMinor) {
    }

    /** Per-source health, as shown on the dashboard and Data Sources screen. */
    public record SourceHealth(String provider,
                               String displayName,
                               String status,
                               boolean configured,
                               java.time.Instant lastSuccessAt,
                               java.time.Instant lastAttemptAt,
                               String lastError,
                               int consecutiveFailures,
                               boolean backfillComplete,
                               LocalDate backfillThrough,
                               String lastRunStatus,
                               Integer lastRunCreated,
                               Integer lastRunExamined) {
    }

    /**
     * What the numbers above are and are not based on.
     *
     * @param connectedSourceCount how many providers are actively syncing
     * @param automaticCount transactions collected without human transcription
     * @param manualCount transactions the user entered by hand
     * @param importedCount transactions from a CSV or statement import
     * @param automaticSharePercent share collected automatically
     * @param uncategorizedCount counted but not filed under a category
     * @param unattributedCount counted but not attributable to an account
     * @param pendingCount authorised but not yet settled
     * @param openReviewCount questions awaiting a human
     * @param parseFailureCount messages that looked like transactions and could
     *     not be read: a direct measure of missed coverage
     * @param exhaustedRecordCount records that ran out of retries
     * @param cashAccountCount accounts no provider can ever observe
     * @param earliestTransaction how far back the ledger reaches
     * @param warnings human-readable statements of what may be missing
     */
    public record Coverage(int connectedSourceCount,
                           long automaticCount,
                           long manualCount,
                           long importedCount,
                           int automaticSharePercent,
                           long uncategorizedCount,
                           long unattributedCount,
                           long pendingCount,
                           long openReviewCount,
                           long parseFailureCount,
                           long exhaustedRecordCount,
                           long cashAccountCount,
                           LocalDate earliestTransaction,
                           List<String> warnings) {
    }

    public record Dashboard(DateRange range,
                            Totals totals,
                            List<CategorySlice> byCategory,
                            List<MerchantSlice> byMerchant,
                            List<PaymentMethodSlice> byPaymentMethod,
                            List<MonthPoint> monthlyTrend,
                            List<AccountService.AccountBalance> accounts,
                            List<AccountService.CreditCardSummary> creditCards,
                            List<CanonicalTransaction> recentTransactions,
                            List<SourceHealth> sources,
                            Coverage coverage) {
    }

    // --- Assembly -----------------------------------------------------------

    public Dashboard build(DateRange range, LocalDate today, int trendMonths) {
        Totals totals = totals(range);
        return new Dashboard(
                range,
                totals,
                categoryBreakdown(range, totals.grossSpendingMinor()),
                merchantBreakdown(range, 10),
                paymentMethodBreakdown(range),
                monthlyTrend(DateRange.trailingMonths(today, trendMonths)),
                accountService.balances(),
                accountService.cardSummaries(today),
                transactions.findTop10ByOrderByOccurredOnDescCreatedAtDesc(),
                sourceHealth(),
                coverage(range));
    }

    public Totals totals(DateRange range) {
        long gross = transactions.sumSpendingMinor(range.from(), range.to());
        long offsets = transactions.sumSpendingOffsetsMinor(range.from(), range.to());
        long income = transactions.sumIncomeMinor(range.from(), range.to());

        Money grossMoney = Money.ofMinor(gross, Money.INR);
        Money offsetMoney = Money.ofMinor(offsets, Money.INR);
        Money net = LedgerMath.netSpending(grossMoney, offsetMoney);
        Money cashFlow = LedgerMath.netCashFlow(Money.ofMinor(income, Money.INR), net);

        // Compare with the equivalent preceding period: the previous calendar
        // month for a month range, otherwise the same number of days before.
        DateRange previous = range.isWholeMonth() ? range.previousMonth() : range.precedingEquivalent();
        long previousGross = transactions.sumSpendingMinor(previous.from(), previous.to());
        long previousOffsets = transactions.sumSpendingOffsetsMinor(previous.from(), previous.to());
        long previousNet = previousGross - previousOffsets;
        long previousIncome = transactions.sumIncomeMinor(previous.from(), previous.to());

        // Null rather than 0% or 100% when there is nothing to compare against:
        // "spending is up 100%" from a month with no data is meaningless.
        Integer changePercent = null;
        if (previousNet > 0) {
            long delta = net.minor() - previousNet;
            changePercent = (int) Math.clamp((delta * 100L) / previousNet,
                    Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        long count = transactions.sumSpendingByCategory(range.from(), range.to()).stream()
                .mapToLong(CanonicalTransactionRepository.CategoryTotal::getTxnCount)
                .sum();

        return new Totals(gross, offsets, net.minor(), income, cashFlow.minor(), Money.INR,
                previousNet, previousIncome, changePercent, count);
    }

    public List<CategorySlice> categoryBreakdown(DateRange range, long totalForShare) {
        List<CategorySlice> slices = new ArrayList<>();
        for (CanonicalTransactionRepository.CategoryTotal row
                : transactions.sumSpendingByCategory(range.from(), range.to())) {
            String categoryId = row.getCategoryId();
            String name = categoryId == null ? "Uncategorized" : categoryService.displayName(categoryId);
            String parentName = null;
            if (categoryId != null) {
                String rootId = categoryService.rootIdOf(categoryId);
                if (rootId != null && !rootId.equals(categoryId)) {
                    parentName = categoryService.displayName(rootId);
                }
            }
            int share = totalForShare > 0
                    ? (int) ((row.getTotalMinor() * 100L) / totalForShare)
                    : 0;
            slices.add(new CategorySlice(categoryId, name, parentName,
                    row.getTotalMinor(), row.getTxnCount(), share));
        }
        return slices;
    }

    public List<MerchantSlice> merchantBreakdown(DateRange range, int limit) {
        List<MerchantSlice> slices = new ArrayList<>();
        for (CanonicalTransactionRepository.MerchantTotal row
                : transactions.sumSpendingByMerchant(range.from(), range.to())) {
            if (slices.size() >= limit) {
                break;
            }
            slices.add(new MerchantSlice(row.getMerchant(), row.getTotalMinor(), row.getTxnCount()));
        }
        return slices;
    }

    public List<PaymentMethodSlice> paymentMethodBreakdown(DateRange range) {
        Map<String, Account> accountsById = new LinkedHashMap<>();
        for (Account account : accountService.findAll()) {
            accountsById.put(account.getId(), account);
        }

        List<PaymentMethodSlice> slices = new ArrayList<>();
        for (CanonicalTransactionRepository.AccountTotal row
                : transactions.sumSpendingByAccount(range.from(), range.to())) {
            String accountId = row.getAccountId();
            if (accountId == null) {
                // Spending the sources could not attribute to an instrument.
                // Reported as its own slice rather than hidden, because it is a
                // real gap in what the app knows.
                slices.add(new PaymentMethodSlice(null, "Unattributed", "UNKNOWN",
                        row.getTotalMinor(), row.getTxnCount()));
                continue;
            }
            Account account = accountsById.get(accountId);
            slices.add(new PaymentMethodSlice(accountId,
                    account == null ? "Removed account" : account.getName(),
                    account == null ? "UNKNOWN" : account.getType().name(),
                    row.getTotalMinor(), row.getTxnCount()));
        }
        return slices;
    }

    public List<MonthPoint> monthlyTrend(DateRange range) {
        List<MonthPoint> points = new ArrayList<>();
        // The native trend query compares the ISO-8601 text column directly.
        for (CanonicalTransactionRepository.MonthlyTotal row
                : transactions.sumMonthlyTotals(range.from().toString(), range.to().toString())) {
            long net = row.getSpendingMinor() - row.getOffsetsMinor();
            points.add(new MonthPoint(row.getPeriod(), row.getSpendingMinor(),
                    row.getIncomeMinor(), net));
        }
        return points;
    }

    public List<SourceHealth> sourceHealth() {
        List<SourceHealth> health = new ArrayList<>();
        for (ProviderConnection connection : connections.findAll()) {
            Optional<SyncRun> lastRun =
                    syncRuns.findFirstByProviderConnectionIdOrderByStartedAtDesc(connection.getId());
            health.add(new SourceHealth(
                    connection.getProvider().name(),
                    connection.getDisplayName(),
                    connection.getStatus().name(),
                    true,
                    connection.getLastSuccessAt(),
                    connection.getLastAttemptAt(),
                    connection.getLastError(),
                    connection.getConsecutiveFailures(),
                    connection.isBackfillComplete(),
                    connection.getBackfillThrough(),
                    lastRun.map(run -> run.getStatus().name()).orElse(null),
                    lastRun.map(SyncRun::getTransactionsCreated).orElse(null),
                    lastRun.map(SyncRun::getMessagesExamined).orElse(null)));
        }
        return health;
    }

    /**
     * Builds the coverage block, including the plain-language warnings.
     *
     * <p>The warnings exist so the user is never left to infer completeness
     * from a confident-looking number. Each one states a specific, checkable
     * reason the total may understate reality.
     */
    public Coverage coverage(DateRange range) {
        Map<TransactionOrigin, Long> byOrigin = new EnumMap<>(TransactionOrigin.class);
        for (TransactionOrigin origin : TransactionOrigin.values()) {
            byOrigin.put(origin, 0L);
        }
        for (CanonicalTransactionRepository.OriginCount row
                : transactions.countByOrigin(range.from(), range.to())) {
            byOrigin.put(row.getOrigin(), row.getTxnCount());
        }

        long automatic = byOrigin.get(TransactionOrigin.AUTOMATIC)
                + byOrigin.get(TransactionOrigin.RECURRING);
        long manual = byOrigin.get(TransactionOrigin.MANUAL);
        long imported = byOrigin.get(TransactionOrigin.CSV_IMPORT);
        long total = automatic + manual + imported;
        int automaticShare = total > 0 ? (int) ((automatic * 100L) / total) : 0;

        List<ProviderConnection> syncable = connections.findSyncable();
        long uncategorized = transactions.countUncategorized(range.from(), range.to());
        long unattributed = transactions.countUnattributedToAccount(range.from(), range.to());
        long pending = transactions.countByStatus(TransactionStatus.PENDING);
        long openReviews = reviewService.openCount();
        long parseFailures = sourceRecords.countByStatus(SourceRecordStatus.PARSE_FAILED);
        long exhausted = sourceRecords.countExhausted(maxAttempts);
        long cashAccounts = accountService.findActive().stream()
                .filter(Account::isManualOnly)
                .count();

        List<String> warnings = new ArrayList<>();

        if (syncable.isEmpty()) {
            warnings.add("No data source is connected, so nothing is being collected automatically. "
                    + "These totals cover only what has been imported or entered by hand.");
        } else {
            for (ProviderConnection connection : connections.findAll()) {
                if (connection.getStatus() == ProviderConnection.Status.NEEDS_REAUTH) {
                    warnings.add(connection.getProvider().name() + " needs to be reconnected. "
                            + "No new transactions have been collected from it since it stopped working.");
                } else if (connection.getStatus() == ProviderConnection.Status.ERROR) {
                    warnings.add(connection.getProvider().name() + " is failing to sync ("
                            + connection.getConsecutiveFailures() + " consecutive failures), "
                            + "so recent transactions may be missing.");
                } else if (connection.getStatus() == ProviderConnection.Status.CONNECTED
                        && connection.getLastSuccessAt() == null) {
                    warnings.add(connection.getProvider().name()
                            + " is connected but has not completed a sync yet.");
                }
                if (connection.getStatus() == ProviderConnection.Status.CONNECTED
                        && !connection.isBackfillComplete()) {
                    warnings.add("The historical import from " + connection.getProvider().name()
                            + " is still in progress"
                            + (connection.getBackfillThrough() == null
                                    ? "" : ", currently back to " + connection.getBackfillThrough())
                            + ", so older periods are incomplete.");
                }
            }
        }

        if (cashAccounts > 0) {
            warnings.add("Cash spending cannot be detected by any automated source. "
                    + "Cash transactions appear only if you enter them.");
        }
        if (parseFailures > 0) {
            warnings.add(parseFailures + " message(s) looked like transactions but could not be read. "
                    + "Spending they describe is missing from these totals - see the review queue.");
        }
        if (unattributed > 0) {
            warnings.add(unattributed + " transaction(s) could not be matched to an account, "
                    + "so per-account balances are incomplete.");
        }
        if (uncategorized > 0) {
            warnings.add(uncategorized + " transaction(s) are uncategorised and appear "
                    + "under 'Uncategorized' in the category breakdown.");
        }
        if (pending > 0) {
            warnings.add(pending + " transaction(s) are authorised but not yet settled; "
                    + "their final amounts may change.");
        }
        if (manual > 0 || imported > 0) {
            warnings.add(automaticShare + "% of transactions in this period were collected "
                    + "automatically; the rest were imported or entered manually.");
        }

        return new Coverage(syncable.size(), automatic, manual, imported, automaticShare,
                uncategorized, unattributed, pending, openReviews, parseFailures, exhausted,
                cashAccounts, transactions.findEarliestDate(), warnings);
    }

    /** Open review counts per kind, for the navigation badge. */
    public Map<ReviewItem.Kind, Long> reviewCounts() {
        return reviewService.openCountsByKind();
    }
}
