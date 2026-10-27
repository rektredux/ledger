package com.ledger.api;

import com.ledger.account.Account;
import com.ledger.account.AccountService;
import com.ledger.account.StatementCycle;
import com.ledger.categorize.CategoryRule;
import com.ledger.categorize.MatchType;
import com.ledger.category.Category;
import com.ledger.category.CategoryService;
import com.ledger.ingest.MatchDecision;
import com.ledger.review.ReviewItem;
import com.ledger.source.SourceRecord;
import com.ledger.txn.CanonicalTransaction;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Request and response shapes for the REST API.
 *
 * <h2>Money on the wire</h2>
 * Amounts cross the API as {@code amountMinor}, an integer count of paise, plus
 * a currency code — never as a decimal number. A JSON number parsed into a
 * JavaScript {@code Number} is a float, so {@code 1234.56} would arrive in the
 * browser as a value that cannot be summed exactly. Integers up to 2^53 are
 * exact in JavaScript, and paise amounts are far below that, so the frontend
 * can total them safely and formats for display only at the last moment.
 *
 * <h2>Why DTOs rather than entities</h2>
 * The API shape is a contract with the frontend and should not shift because a
 * column was renamed. These records also carry resolved display values — a
 * category's name as well as its id — so the UI does not have to join
 * client-side on every render.
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    // --- Transactions -------------------------------------------------------

    /**
     * A transaction as the UI sees it.
     *
     * @param categorySource   how the category was decided, so the UI can mark
     *     a guess as a guess rather than presenting it as fact
     * @param categoryConfidence 0 to 1; exactly 1 when the user chose it
     * @param isSpending       whether this contributes to spending totals,
     *     computed server-side so the client never has to re-derive the
     *     accounting rules
     * @param sourceCount      how many independent observations evidence it
     */
    public record TransactionDto(String id,
                                 long amountMinor,
                                 String currency,
                                 LocalDate occurredOn,
                                 LocalTime occurredAtTime,
                                 LocalDate postedOn,
                                 String type,
                                 String typeLabel,
                                 String status,
                                 String merchant,
                                 String merchantRaw,
                                 String categoryId,
                                 String categoryName,
                                 String categoryPath,
                                 String categorySource,
                                 double categoryConfidence,
                                 String accountId,
                                 String accountName,
                                 String counterAccountId,
                                 String counterAccountName,
                                 String description,
                                 String notes,
                                 String externalReference,
                                 String refundOfId,
                                 String reversesId,
                                 String reconciliationStatus,
                                 String origin,
                                 boolean isSpending,
                                 boolean isIncome,
                                 int sourceCount,
                                 Instant createdAt,
                                 Instant updatedAt) {
    }

    /** A page of transactions. */
    public record PageDto<T>(List<T> items,
                             int page,
                             int size,
                             long totalItems,
                             int totalPages) {
    }

    /** Create or update a transaction by hand (the exception workflow). */
    public record TransactionRequest(@NotNull @Positive Long amountMinor,
                                     String currency,
                                     @NotNull LocalDate occurredOn,
                                     @NotBlank String type,
                                     String accountId,
                                     String counterAccountId,
                                     String merchant,
                                     String categoryId,
                                     String notes) {
    }

    public record RecategorizeRequest(@NotBlank String categoryId,
                                      /**
                                       * When true, a rule is written so future
                                       * transactions from this merchant are
                                       * categorised the same way.
                                       */
                                      boolean learn) {
    }

    public record LinkRefundRequest(@NotBlank String originalTransactionId) {
    }

    // --- Evidence and audit -------------------------------------------------

    /**
     * One observation behind a transaction.
     *
     * <p>{@code redactedExcerpt} is a short, masked fragment of the original
     * message, retained so the user can see why a record parsed as it did. The
     * full body is never stored.
     */
    public record SourceRecordDto(String id,
                                  String provider,
                                  String status,
                                  String sender,
                                  String subject,
                                  Instant receivedAt,
                                  String redactedExcerpt,
                                  String parserId,
                                  Double parseConfidence,
                                  String parseError,
                                  Long extractedAmountMinor,
                                  LocalDate extractedOccurredOn,
                                  String extractedMerchant,
                                  String extractedType,
                                  String extractedReference,
                                  String extractedInstrumentHint,
                                  int attemptCount,
                                  Instant nextAttemptAt,
                                  String canonicalTransactionId) {
    }

    /** One deduplication decision, with its reasoning. */
    public record MatchDecisionDto(String id,
                                   String sourceRecordId,
                                   String canonicalTransactionId,
                                   String decision,
                                   String strategy,
                                   Double score,
                                   String rationale,
                                   Instant decidedAt,
                                   String decidedBy) {
    }

    /** A transaction with its full evidence trail. */
    public record TransactionDetailDto(TransactionDto transaction,
                                       List<SourceRecordDto> sources,
                                       List<MatchDecisionDto> decisions,
                                       List<TransactionDto> relatedRefunds) {
    }

    // --- Accounts -----------------------------------------------------------

    /**
     * An account with its derived balance.
     *
     * @param balanceConfidence what the balance is based on; the UI shows this
     *     so a derived figure is not mistaken for a bank-confirmed one
     */
    public record AccountDto(String id,
                             String name,
                             String type,
                             String currency,
                             String institution,
                             String instrumentHint,
                             long openingBalanceMinor,
                             Long creditLimitMinor,
                             Integer statementDay,
                             Integer dueDay,
                             boolean archived,
                             long netMovementMinor,
                             long balanceMinor,
                             String balanceConfidence,
                             boolean isLiability,
                             boolean manualOnly) {
    }

    public record AccountRequest(@NotBlank String name,
                                 @NotBlank String type,
                                 String currency,
                                 String institution,
                                 String instrumentHint,
                                 Long openingBalanceMinor,
                                 Long creditLimitMinor,
                                 @Min(1) Integer statementDay,
                                 @Min(1) Integer dueDay) {
    }

    /** Credit card summary. Nulls mean "not configured", not zero. */
    public record CreditCardDto(String accountId,
                                String name,
                                String institution,
                                String instrumentHint,
                                String currency,
                                long outstandingMinor,
                                Long creditLimitMinor,
                                Long availableCreditMinor,
                                Integer utilisationPercent,
                                LocalDate currentCycleFrom,
                                LocalDate currentCycleTo,
                                LocalDate currentCycleDueOn,
                                Long currentCycleSpendingMinor,
                                LocalDate previousCycleFrom,
                                LocalDate previousCycleTo,
                                Long previousCycleSpendingMinor,
                                boolean cycleConfigured) {
    }

    // --- Categories ---------------------------------------------------------

    public record CategoryDto(String id,
                              String name,
                              String parentId,
                              String path,
                              boolean excludedFromSpending,
                              boolean system,
                              String icon,
                              List<CategoryDto> children) {
    }

    public record CategoryRequest(@NotBlank String name,
                                  String parentId,
                                  boolean excludedFromSpending) {
    }

    // --- Categorization rules ----------------------------------------------

    public record CategoryRuleDto(String id,
                                  String pattern,
                                  String matchType,
                                  String categoryId,
                                  String categoryPath,
                                  int priority,
                                  double confidence,
                                  String origin,
                                  boolean enabled,
                                  long hitCount) {
    }

    public record CategoryRuleRequest(@NotBlank String pattern,
                                      String matchType,
                                      @NotBlank String categoryId,
                                      Double confidence) {
    }

    // --- Budgets ------------------------------------------------------------

    public record BudgetRequest(String categoryId,
                                @NotNull @Positive Long amountMinor,
                                /** {@code yyyy-MM}, or null for the recurring default. */
                                String period,
                                Integer warnAtPercent) {
    }

    // --- Recurring ----------------------------------------------------------

    public record RecurringRequest(@NotBlank String name,
                                   @NotNull @Positive Long amountMinor,
                                   @NotBlank String cadence,
                                   @NotNull @Min(1) Integer dayOfPeriod,
                                   LocalDate startsOn,
                                   String categoryId,
                                   String accountId,
                                   String merchant,
                                   Integer monthOfYear) {
    }

    // --- Review queue -------------------------------------------------------

    public record ReviewItemDto(String id,
                                String kind,
                                String status,
                                String summary,
                                String sourceRecordId,
                                String canonicalTransactionId,
                                List<CandidateDto> candidates,
                                SourceRecordDto sourceRecord,
                                Instant createdAt) {
    }

    public record CandidateDto(String transactionId,
                               double score,
                               String rationale,
                               long amountMinor,
                               String currency,
                               LocalDate occurredOn,
                               String merchant) {
    }

    /**
     * Resolves an ambiguous match.
     *
     * @param action   {@code LINK} to attach the record to an existing
     *     transaction, {@code CREATE} to accept it as a separate transaction,
     *     or {@code DISMISS} to leave it unlinked
     * @param transactionId the chosen transaction, required for {@code LINK}
     */
    public record ResolveReviewRequest(@NotBlank String action,
                                       String transactionId,
                                       String note) {
    }

    // --- Mappers ------------------------------------------------------------

    public static CategoryDto toCategoryDto(Category category, CategoryService categories,
                                            List<CategoryDto> children) {
        return new CategoryDto(category.getId(), category.getName(), category.getParentId(),
                categories.displayPath(category.getId()),
                categories.isExcludedFromSpending(category.getId()),
                category.isSystem(), category.getIcon(), children);
    }

    public static CategoryRuleDto toRuleDto(CategoryRule rule, CategoryService categories) {
        return new CategoryRuleDto(rule.getId(), rule.getPattern(), rule.getMatchType().name(),
                rule.getCategoryId(), categories.displayPath(rule.getCategoryId()),
                rule.getPriority(), rule.getConfidence(), rule.getOrigin().name(),
                rule.isEnabled(), rule.getHitCount());
    }

    public static SourceRecordDto toSourceRecordDto(SourceRecord record) {
        return new SourceRecordDto(record.getId(), record.getProvider().name(),
                record.getStatus().name(), record.getSender(), record.getSubject(),
                record.getReceivedAt(), record.getRedactedExcerpt(), record.getParserId(),
                record.getParseConfidence(), record.getParseError(),
                record.getExtractedAmountMinor(), record.getExtractedOccurredOn(),
                record.getExtractedMerchant(),
                record.getExtractedType() == null ? null : record.getExtractedType().name(),
                record.getExtractedReference(), record.getExtractedInstrumentHint(),
                record.getAttemptCount(), record.getNextAttemptAt(),
                record.getCanonicalTransactionId());
    }

    public static MatchDecisionDto toDecisionDto(MatchDecision decision) {
        return new MatchDecisionDto(decision.getId(), decision.getSourceRecordId(),
                decision.getCanonicalTransactionId(), decision.getDecision().name(),
                decision.getStrategy().name(), decision.getScore(), decision.getRationale(),
                decision.getDecidedAt(), decision.getDecidedBy());
    }

    public static AccountDto toAccountDto(AccountService.AccountBalance balance) {
        Account account = balance.account();
        return new AccountDto(account.getId(), account.getName(), account.getType().name(),
                account.getCurrency(), account.getInstitution(), account.getInstrumentHint(),
                account.getOpeningBalanceMinor(), account.getCreditLimitMinor(),
                account.getStatementDay(), account.getDueDay(), account.isArchived(),
                balance.netMovement().minor(), balance.balance().minor(),
                balance.confidence().name(), account.getType().isLiability(),
                account.isManualOnly());
    }

    public static CreditCardDto toCreditCardDto(AccountService.CreditCardSummary summary) {
        Account card = summary.account();
        StatementCycle current = summary.currentCycle();
        StatementCycle previous = summary.previousCycle();
        return new CreditCardDto(card.getId(), card.getName(), card.getInstitution(),
                card.getInstrumentHint(), card.getCurrency(),
                summary.outstanding().minor(),
                summary.creditLimit() == null ? null : summary.creditLimit().minor(),
                summary.availableCredit() == null ? null : summary.availableCredit().minor(),
                summary.utilisationPercent(),
                current == null ? null : current.from(),
                current == null ? null : current.to(),
                current == null ? null : current.dueOn(),
                summary.currentCycleSpending() == null ? null : summary.currentCycleSpending().minor(),
                previous == null ? null : previous.from(),
                previous == null ? null : previous.to(),
                summary.previousCycleSpending() == null ? null : summary.previousCycleSpending().minor(),
                summary.cycleConfigured());
    }

    public static ReviewItemDto toReviewDto(ReviewItem item,
                                            List<CandidateDto> candidates,
                                            SourceRecordDto sourceRecord) {
        return new ReviewItemDto(item.getId(), item.getKind().name(), item.getStatus().name(),
                item.getSummary(), item.getSourceRecordId(), item.getCanonicalTransactionId(),
                candidates, sourceRecord, item.getCreatedAt());
    }

    public static MatchType parseMatchType(String raw) {
        return MatchType.parse(raw);
    }
}
