package com.ledger.dataio;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.account.Account;
import com.ledger.account.AccountRepository;
import com.ledger.budget.Budget;
import com.ledger.budget.BudgetRepository;
import com.ledger.category.Category;
import com.ledger.category.CategoryRepository;
import com.ledger.category.CategoryService;
import com.ledger.categorize.CategorizationEngine;
import com.ledger.categorize.CategoryRule;
import com.ledger.categorize.CategoryRuleRepository;
import com.ledger.categorize.MerchantAlias;
import com.ledger.categorize.MerchantAliasRepository;
import com.ledger.categorize.MerchantNormalizer;
import com.ledger.ingest.MatchDecision;
import com.ledger.ingest.MatchDecisionRepository;
import com.ledger.recurring.RecurringExpense;
import com.ledger.recurring.RecurringExpenseRepository;
import com.ledger.recurring.RecurringOccurrence;
import com.ledger.recurring.RecurringOccurrenceRepository;
import com.ledger.review.ReviewItem;
import com.ledger.review.ReviewItemRepository;
import com.ledger.source.SourceRecord;
import com.ledger.source.SourceRecordRepository;
import com.ledger.txn.CanonicalTransaction;
import com.ledger.txn.CanonicalTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON backup and restore of the application's own data.
 *
 * <h2>What is included, and why</h2>
 * Everything needed to reconstruct the ledger and explain it: accounts,
 * categories, transactions, the source records that evidence them, the match
 * decisions that linked them, categorisation rules, budgets, recurring
 * definitions and open review items.
 *
 * <p>Source records and match decisions are included deliberately. Without
 * them a restored ledger would hold the numbers but lose the ability to show
 * <em>why</em> a transaction exists or why two observations were merged — the
 * audit trail would survive only until the first backup/restore cycle.
 *
 * <h2>What is excluded</h2>
 * Provider connections and OAuth tokens. A backup is a file that gets copied
 * to other disks and cloud folders; putting a live mailbox credential in it
 * would turn every stray copy into mailbox access. After a restore the user
 * reconnects Gmail, which costs one consent screen and is the right trade.
 *
 * <h2>Restore is explicit about destruction</h2>
 * {@link #restore} requires a mode. {@link RestoreMode#MERGE} adds only rows
 * whose ids are absent. {@link RestoreMode#REPLACE} wipes existing data first
 * and must be asked for by name — nothing is overwritten by default.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** Bumped when the payload shape changes incompatibly. */
    public static final int FORMAT_VERSION = 1;

    public enum RestoreMode {
        /**
         * Insert rows whose ids are not already present; leave existing rows
         * untouched. Safe to run against a populated database.
         */
        MERGE,
        /**
         * Delete all existing ledger data, then insert. Destructive, and the
         * API requires explicit confirmation before calling it.
         */
        REPLACE
    }

    /** The backup document. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Backup(int formatVersion,
                         Instant exportedAt,
                         String application,
                         Counts counts,
                         List<Account> accounts,
                         List<Category> categories,
                         List<CanonicalTransaction> transactions,
                         List<SourceRecord> sourceRecords,
                         List<MatchDecision> matchDecisions,
                         List<CategoryRule> categoryRules,
                         List<MerchantAlias> merchantAliases,
                         List<Budget> budgets,
                         List<RecurringExpense> recurringExpenses,
                         List<RecurringOccurrence> recurringOccurrences,
                         List<ReviewItem> reviewItems) {
    }

    public record Counts(int accounts,
                         int categories,
                         int transactions,
                         int sourceRecords,
                         int matchDecisions,
                         int categoryRules,
                         int merchantAliases,
                         int budgets,
                         int recurringExpenses,
                         int reviewItems) {
    }

    /** Outcome of a restore. */
    public record RestoreReport(RestoreMode mode,
                                int accountsRestored,
                                int categoriesRestored,
                                int transactionsRestored,
                                int sourceRecordsRestored,
                                int rulesRestored,
                                int budgetsRestored,
                                int skippedExisting,
                                List<String> warnings) {
    }

    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final CanonicalTransactionRepository transactions;
    private final SourceRecordRepository sourceRecords;
    private final MatchDecisionRepository matchDecisions;
    private final CategoryRuleRepository categoryRules;
    private final MerchantAliasRepository merchantAliases;
    private final BudgetRepository budgets;
    private final RecurringExpenseRepository recurringExpenses;
    private final RecurringOccurrenceRepository recurringOccurrences;
    private final ReviewItemRepository reviewItems;
    private final ObjectMapper objectMapper;
    private final CategoryService categoryService;
    private final CategorizationEngine categorizationEngine;
    private final MerchantNormalizer merchantNormalizer;

    public BackupService(AccountRepository accounts,
                         CategoryRepository categories,
                         CanonicalTransactionRepository transactions,
                         SourceRecordRepository sourceRecords,
                         MatchDecisionRepository matchDecisions,
                         CategoryRuleRepository categoryRules,
                         MerchantAliasRepository merchantAliases,
                         BudgetRepository budgets,
                         RecurringExpenseRepository recurringExpenses,
                         RecurringOccurrenceRepository recurringOccurrences,
                         ReviewItemRepository reviewItems,
                         @org.springframework.beans.factory.annotation.Qualifier("backupObjectMapper")
                         ObjectMapper objectMapper,
                         CategoryService categoryService,
                         CategorizationEngine categorizationEngine,
                         MerchantNormalizer merchantNormalizer) {
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.sourceRecords = sourceRecords;
        this.matchDecisions = matchDecisions;
        this.categoryRules = categoryRules;
        this.merchantAliases = merchantAliases;
        this.budgets = budgets;
        this.recurringExpenses = recurringExpenses;
        this.recurringOccurrences = recurringOccurrences;
        this.reviewItems = reviewItems;
        this.objectMapper = objectMapper;
        this.categoryService = categoryService;
        this.categorizationEngine = categorizationEngine;
        this.merchantNormalizer = merchantNormalizer;
    }

    // --- Export -------------------------------------------------------------

    public Backup export() {
        List<Account> accountList = accounts.findAll();
        List<Category> categoryList = categories.findAll();
        List<CanonicalTransaction> transactionList = transactions.findAll();
        List<SourceRecord> sourceRecordList = sourceRecords.findAll();
        List<MatchDecision> decisionList = matchDecisions.findAll();
        List<CategoryRule> ruleList = categoryRules.findAll();
        List<MerchantAlias> aliasList = merchantAliases.findAll();
        List<Budget> budgetList = budgets.findAll();
        List<RecurringExpense> recurringList = recurringExpenses.findAll();
        List<RecurringOccurrence> occurrenceList = recurringOccurrences.findAll();
        List<ReviewItem> reviewList = reviewItems.findAll();

        Counts counts = new Counts(accountList.size(), categoryList.size(), transactionList.size(),
                sourceRecordList.size(), decisionList.size(), ruleList.size(), aliasList.size(),
                budgetList.size(), recurringList.size(), reviewList.size());

        return new Backup(FORMAT_VERSION, Instant.now(), "ledger", counts,
                accountList, categoryList, transactionList, sourceRecordList, decisionList,
                ruleList, aliasList, budgetList, recurringList, occurrenceList, reviewList);
    }

    public String exportJson() throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export());
    }

    // --- Restore ------------------------------------------------------------

    /**
     * Restores a backup.
     *
     * <p>Insert order follows the foreign keys: accounts and categories first,
     * then transactions, then the records and decisions that point at them.
     * Getting that order wrong would fail against the enabled foreign key
     * constraints — which is the point of having them on.
     *
     * @throws IllegalArgumentException when the payload is not a readable
     *     backup of a supported version. Failing loudly beats importing half a
     *     file.
     */
    @Transactional
    public RestoreReport restore(InputStream json, RestoreMode mode) throws IOException {
        Backup backup;
        try {
            backup = objectMapper.readValue(json, Backup.class);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "this file is not a readable Ledger backup: " + describeJsonFailure(e), e);
        }

        if (backup.application() != null && !"ledger".equals(backup.application())) {
            throw new IllegalArgumentException(
                    "this backup was produced by '" + backup.application() + "', not Ledger");
        }
        if (backup.formatVersion() > FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "this backup is format version " + backup.formatVersion()
                            + ", which is newer than this application supports (" + FORMAT_VERSION
                            + "). Upgrade Ledger and try again.");
        }

        List<String> warnings = new ArrayList<>();

        if (mode == RestoreMode.REPLACE) {
            deleteAllLedgerData();
            warnings.add("Existing ledger data was deleted before this restore.");
        }

        int skipped = 0;
        int accountsRestored = 0;
        int categoriesRestored = 0;
        int transactionsRestored = 0;
        int sourceRecordsRestored = 0;
        int rulesRestored = 0;
        int budgetsRestored = 0;

        // Categories before accounts is not required by the keys, but both must
        // precede transactions.
        for (Category category : nullSafe(backup.categories())) {
            if (categories.existsById(category.getId())) {
                skipped++;
                continue;
            }
            categories.save(category);
            categoriesRestored++;
        }
        for (Account account : nullSafe(backup.accounts())) {
            if (accounts.existsById(account.getId())) {
                skipped++;
                continue;
            }
            accounts.save(account);
            accountsRestored++;
        }

        // Transactions reference each other through refundOfId / reversesId, so
        // they are saved in two passes: first without the self-references, then
        // with them. A single pass would fail whenever a refund happened to be
        // written before its original.
        List<PendingLink> pendingLinks = new ArrayList<>();
        for (CanonicalTransaction txn : nullSafe(backup.transactions())) {
            if (transactions.existsById(txn.getId())) {
                skipped++;
                continue;
            }
            if (txn.getRefundOfId() != null || txn.getReversesId() != null) {
                pendingLinks.add(new PendingLink(
                        txn.getId(), txn.getRefundOfId(), txn.getReversesId()));
                txn.setRefundOfId(null);
                txn.setReversesId(null);
            }
            transactions.save(txn);
            transactionsRestored++;
        }
        for (PendingLink linked : pendingLinks) {
            transactions.findById(linked.id()).ifPresent(saved -> {
                if (linked.refundOfId() != null && transactions.existsById(linked.refundOfId())) {
                    saved.setRefundOfId(linked.refundOfId());
                }
                if (linked.reversesId() != null && transactions.existsById(linked.reversesId())) {
                    saved.setReversesId(linked.reversesId());
                }
                transactions.save(saved);
            });
        }

        for (SourceRecord record : nullSafe(backup.sourceRecords())) {
            if (sourceRecords.existsById(record.getId())) {
                skipped++;
                continue;
            }
            // A record pointing at a transaction that is not in this backup
            // would violate the foreign key. Keep the record (it is evidence)
            // but detach it, and say so.
            if (record.getCanonicalTransactionId() != null
                    && !transactions.existsById(record.getCanonicalTransactionId())) {
                record.setCanonicalTransactionId(null);
                warnings.add("Source record " + record.getId()
                        + " referenced a transaction that is not in this backup; it was restored unlinked.");
            }
            sourceRecords.save(record);
            sourceRecordsRestored++;
        }

        for (MatchDecision decision : nullSafe(backup.matchDecisions())) {
            if (matchDecisions.existsById(decision.getId())) {
                continue;
            }
            if (!sourceRecords.existsById(decision.getSourceRecordId())) {
                continue;
            }
            if (decision.getCanonicalTransactionId() != null
                    && !transactions.existsById(decision.getCanonicalTransactionId())) {
                decision.setCanonicalTransactionId(null);
            }
            matchDecisions.save(decision);
        }

        for (MerchantAlias alias : nullSafe(backup.merchantAliases())) {
            if (!merchantAliases.existsById(alias.getId())) {
                merchantAliases.save(alias);
            }
        }
        for (CategoryRule rule : nullSafe(backup.categoryRules())) {
            if (categoryRules.existsById(rule.getId())) {
                skipped++;
                continue;
            }
            if (!categories.existsById(rule.getCategoryId())) {
                warnings.add("Category rule " + rule.getId()
                        + " referenced a missing category and was skipped.");
                continue;
            }
            categoryRules.save(rule);
            rulesRestored++;
        }
        for (Budget budget : nullSafe(backup.budgets())) {
            if (budgets.existsById(budget.getId())) {
                skipped++;
                continue;
            }
            budgets.save(budget);
            budgetsRestored++;
        }
        for (RecurringExpense definition : nullSafe(backup.recurringExpenses())) {
            if (!recurringExpenses.existsById(definition.getId())) {
                recurringExpenses.save(definition);
            }
        }
        for (RecurringOccurrence occurrence : nullSafe(backup.recurringOccurrences())) {
            if (recurringOccurrences.existsById(occurrence.getId())) {
                continue;
            }
            if (!recurringExpenses.existsById(occurrence.getRecurringExpenseId())) {
                continue;
            }
            if (occurrence.getMatchedTransactionId() != null
                    && !transactions.existsById(occurrence.getMatchedTransactionId())) {
                occurrence.setMatchedTransactionId(null);
                occurrence.setStatus(RecurringOccurrence.Status.EXPECTED);
            }
            recurringOccurrences.save(occurrence);
        }
        for (ReviewItem item : nullSafe(backup.reviewItems())) {
            if (reviewItems.existsById(item.getId())) {
                continue;
            }
            if (item.getSourceRecordId() != null && !sourceRecords.existsById(item.getSourceRecordId())) {
                continue;
            }
            if (item.getCanonicalTransactionId() != null
                    && !transactions.existsById(item.getCanonicalTransactionId())) {
                continue;
            }
            reviewItems.save(item);
        }

        warnings.add("Provider connections and OAuth tokens are never included in a backup. "
                + "Reconnect Gmail in Data Sources to resume automatic collection.");

        // The in-memory caches now describe data that no longer exists.
        categoryService.invalidate();
        categorizationEngine.invalidate();
        merchantNormalizer.invalidate();

        log.info("Restore ({}) complete: {} transactions, {} source records, {} skipped",
                mode, transactionsRestored, sourceRecordsRestored, skipped);

        return new RestoreReport(mode, accountsRestored, categoriesRestored, transactionsRestored,
                sourceRecordsRestored, rulesRestored, budgetsRestored, skipped, warnings);
    }

    /**
     * Summarises a backup without writing anything, so the UI can show what a
     * restore would involve before the user commits to it.
     */
    public Backup inspect(InputStream json) throws IOException {
        try {
            return objectMapper.readValue(json, Backup.class);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "this file is not a readable Ledger backup: " + describeJsonFailure(e), e);
        }
    }

    /** Deletes ledger data in foreign-key-safe order. */
    private void deleteAllLedgerData() {
        reviewItems.deleteAll();
        matchDecisions.deleteAll();
        recurringOccurrences.deleteAll();
        sourceRecords.deleteAll();
        transactions.deleteAll();
        recurringExpenses.deleteAll();
        budgets.deleteAll();
        accounts.deleteAll();
        // Seeded categories, rules and aliases are left in place: they ship
        // with the application and the backup will re-insert any the user
        // added or changed.
    }

    /**
     * A concise reason a backup file could not be read.
     *
     * <p>Jackson's own message can run to several lines and quote a fragment of
     * the input. The first line is the useful part; the rest only makes the
     * error harder to act on.
     */
    private static String describeJsonFailure(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        String firstLine = newline > 0 ? message.substring(0, newline) : message;
        return firstLine.length() > 200 ? firstLine.substring(0, 200) + "..." : firstLine;
    }

    /**
     * The self-references of one transaction, remembered across the two-pass
     * insert. A record rather than a detached entity: the entity's no-arg
     * constructor is {@code protected} for JPA's benefit and is not meant to be
     * called from here.
     */
    private record PendingLink(String id, String refundOfId, String reversesId) {
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
