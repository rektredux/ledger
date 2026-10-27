package com.ledger.txn;

import com.ledger.account.AccountService;
import com.ledger.categorize.CategorizationEngine;
import com.ledger.categorize.MerchantNormalizer;
import com.ledger.category.CategoryService;
import com.ledger.money.Money;
import com.ledger.review.ReviewService;
import com.ledger.source.SourceRecord;
import com.ledger.source.SourceRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Creating and amending canonical transactions.
 *
 * <p>Sits between the ingestion pipeline (which knows about providers and
 * duplication) and the repository (which knows about SQL). Its job is to make
 * sure that however a transaction arrives — automatic ingestion, CSV import, or
 * the manual exception form — it is categorised, attributed and validated the
 * same way.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final CanonicalTransactionRepository transactions;
    private final SourceRecordRepository sourceRecords;
    private final CategorizationEngine categorizer;
    private final MerchantNormalizer merchantNormalizer;
    private final CategoryService categories;
    private final AccountService accountService;
    private final ReviewService reviewService;

    public TransactionService(CanonicalTransactionRepository transactions,
                              SourceRecordRepository sourceRecords,
                              CategorizationEngine categorizer,
                              MerchantNormalizer merchantNormalizer,
                              CategoryService categories,
                              AccountService accountService,
                              ReviewService reviewService) {
        this.transactions = transactions;
        this.sourceRecords = sourceRecords;
        this.categorizer = categorizer;
        this.merchantNormalizer = merchantNormalizer;
        this.categories = categories;
        this.accountService = accountService;
        this.reviewService = reviewService;
    }

    // --- Creation -----------------------------------------------------------

    /**
     * Builds a canonical transaction from a parsed observation and stores it.
     *
     * <p>Categorisation happens here rather than in the parser, because it is
     * inference rather than extraction and must be recorded with a confidence.
     * A cash withdrawal is forced into the non-spending
     * {@code transfer.cash} category regardless of what the rules say: the
     * money left the account but was not consumed, and counting it as spending
     * would double-count whatever the cash is later spent on.
     *
     * @param cashWithdrawal whether the source described taking cash out
     */
    @Transactional
    public CanonicalTransaction createFromSource(SourceRecord record,
                                                 String resolvedAccountId,
                                                 boolean cashWithdrawal,
                                                 TransactionOrigin origin) {
        if (!record.hasMinimumFields()) {
            throw new IllegalArgumentException(
                    "source record " + record.getId() + " lacks amount, date or type");
        }

        Money amount = record.extractedAmount();
        CanonicalTransaction txn = new CanonicalTransaction(
                amount, record.getExtractedOccurredOn(), record.getExtractedType());

        txn.setAccountId(resolvedAccountId);
        txn.setExternalReference(record.getExtractedReference());
        txn.setOrigin(origin);
        txn.setMerchantRaw(record.getExtractedMerchant());
        txn.setMerchant(merchantNormalizer.normalize(record.getExtractedMerchant()));
        // Carry the parsed settlement state through. Forcing POSTED here would
        // discard the parser's work and, worse, leave no pending transaction
        // for a later settlement alert to match against -- so the hold and the
        // final charge would become two separate expenses.
        txn.setStatus(record.effectiveStatus());
        txn.setDescription(buildDescription(record, txn));

        applyCategory(txn, cashWithdrawal);
        CanonicalTransaction saved = transactions.save(txn);

        if (txn.getCategorySource() == CategorySource.UNCATEGORIZED) {
            reviewService.raiseUnknownCategory(saved.getId(),
                    "No category rule matched " + describeForReview(saved));
        }
        return saved;
    }

    /**
     * Creates a transaction the user entered by hand.
     *
     * <p>This is the exception workflow for spending no source can observe:
     * cash, or an institution that sends no alerts. It is marked
     * {@link TransactionOrigin#MANUAL} so the dashboard can report honestly
     * what share of the ledger was collected automatically.
     */
    @Transactional
    public CanonicalTransaction createManual(Money amount,
                                             LocalDate occurredOn,
                                             TransactionType type,
                                             String accountId,
                                             String counterAccountId,
                                             String merchantRaw,
                                             String categoryId,
                                             String notes) {
        validateCounterAccount(type, accountId, counterAccountId);

        CanonicalTransaction txn = new CanonicalTransaction(amount, occurredOn, type);
        txn.setAccountId(accountId);
        txn.setCounterAccountId(counterAccountId);
        txn.setMerchantRaw(merchantRaw);
        txn.setMerchant(merchantNormalizer.normalize(merchantRaw));
        txn.setNotes(notes);
        txn.setOrigin(TransactionOrigin.MANUAL);
        // A manually entered transaction is first-hand evidence from the user,
        // so it needs no corroboration to be considered settled.
        txn.setReconciliationStatus(ReconciliationStatus.CONFIRMED);

        if (categoryId != null) {
            txn.setCategoryId(categoryId);
            txn.setCategorySource(CategorySource.USER);
            txn.setCategoryConfidence(1.0);
        } else {
            applyCategory(txn, false);
        }
        txn.setDescription(TransactionDescriptions.describe(txn));
        return transactions.save(txn);
    }

    /**
     * Applies automatic categorisation, unless the user has already chosen.
     *
     * @param cashWithdrawal forces the non-spending cash category
     */
    private void applyCategory(CanonicalTransaction txn, boolean cashWithdrawal) {
        if (txn.hasUserCategory()) {
            return;
        }

        if (cashWithdrawal) {
            txn.setCategoryId("transfer.cash");
            txn.setCategorySource(CategorySource.RULE);
            txn.setCategoryConfidence(0.95);
            return;
        }

        // A card bill payment and a transfer have exactly one correct category
        // each, and it is determined by the transaction type rather than by the
        // merchant. Leaving these to the merchant rules would let a payment to
        // "HDFC" be filed as a bill.
        if (txn.getType() == TransactionType.CARD_PAYMENT) {
            txn.setCategoryId(CategoryService.CARD_PAYMENT_CATEGORY_ID);
            txn.setCategorySource(CategorySource.RULE);
            txn.setCategoryConfidence(1.0);
            return;
        }
        if (txn.getType() == TransactionType.TRANSFER) {
            txn.setCategoryId(CategoryService.SELF_TRANSFER_CATEGORY_ID);
            txn.setCategorySource(CategorySource.RULE);
            txn.setCategoryConfidence(1.0);
            return;
        }

        CategorizationEngine.Categorization result =
                categorizer.categorize(txn.getMerchant(), txn.getDescription());
        txn.setCategoryId(result.categoryId());
        txn.setCategorySource(result.source());
        txn.setCategoryConfidence(result.confidence());
        if (result.ruleId() != null) {
            categorizer.recordHit(result.ruleId());
        }
    }

    // --- Amendment ----------------------------------------------------------

    /**
     * Changes a transaction's category at the user's request and, optionally,
     * learns the preference for future transactions from the same merchant.
     *
     * @param learn when true, writes a {@code LEARNED} rule so the next
     *     transaction from this merchant is categorised the same way
     */
    @Transactional
    public CanonicalTransaction recategorize(String id, String categoryId, boolean learn) {
        CanonicalTransaction txn = require(id);
        if (categories.find(categoryId).isEmpty()) {
            throw new IllegalArgumentException("unknown category: " + categoryId);
        }

        txn.setCategoryId(categoryId);
        txn.setCategorySource(CategorySource.USER);
        txn.setCategoryConfidence(1.0);
        CanonicalTransaction saved = transactions.save(txn);

        reviewService.resolveCategoryQuestion(id, "recategorised by user");

        if (learn && txn.getMerchant() != null) {
            categorizer.learnFrom(txn.getMerchant(), categoryId);
        }
        return saved;
    }

    /** Updates the fields a user is allowed to edit. */
    @Transactional
    public CanonicalTransaction update(String id,
                                       Money amount,
                                       LocalDate occurredOn,
                                       TransactionType type,
                                       String accountId,
                                       String counterAccountId,
                                       String categoryId,
                                       String notes) {
        CanonicalTransaction txn = require(id);
        validateCounterAccount(type, accountId, counterAccountId);

        if (amount != null) {
            txn.setAmount(amount);
        }
        if (occurredOn != null) {
            txn.setOccurredOn(occurredOn);
        }
        if (type != null) {
            txn.setType(type);
        }
        txn.setAccountId(accountId);
        txn.setCounterAccountId(counterAccountId);
        txn.setNotes(notes);

        if (categoryId != null && !categoryId.equals(txn.getCategoryId())) {
            txn.setCategoryId(categoryId);
            txn.setCategorySource(CategorySource.USER);
            txn.setCategoryConfidence(1.0);
        }
        // A user edit is an explicit statement about this transaction, which
        // supersedes whatever the automatic matcher concluded.
        txn.setReconciliationStatus(ReconciliationStatus.CONFIRMED);
        return transactions.save(txn);
    }

    /**
     * Links a refund to the purchase it reverses.
     *
     * <p>Required so that a refund reduces the right category rather than
     * floating free, and so the UI can show the pair together.
     */
    @Transactional
    public CanonicalTransaction linkRefund(String refundId, String originalId) {
        CanonicalTransaction refund = require(refundId);
        CanonicalTransaction original = require(originalId);

        if (refund.getType() != TransactionType.REFUND && refund.getType() != TransactionType.REVERSAL) {
            throw new IllegalArgumentException("only a refund or reversal can be linked to an original");
        }
        if (original.getType() != TransactionType.EXPENSE) {
            throw new IllegalArgumentException("a refund can only be linked to an expense");
        }
        if (refund.getAmountMinor() > original.getAmountMinor()) {
            throw new IllegalArgumentException(
                    "refund of " + refund.amount() + " exceeds the original " + original.amount());
        }

        if (refund.getType() == TransactionType.REFUND) {
            refund.setRefundOfId(originalId);
        } else {
            refund.setReversesId(originalId);
        }
        // Inherit the original's category so the offset lands in the same
        // bucket the spending was counted in.
        if (!refund.hasUserCategory() && original.getCategoryId() != null) {
            refund.setCategoryId(original.getCategoryId());
            refund.setCategorySource(original.getCategorySource());
            refund.setCategoryConfidence(original.getCategoryConfidence());
        }
        if (refund.getAccountId() == null) {
            refund.setAccountId(original.getAccountId());
        }
        return transactions.save(refund);
    }

    /**
     * Finds the purchase a refund most likely belongs to.
     *
     * <p>Looks back 120 days for an expense on the same account, with the same
     * merchant, for at least the refund amount. Returns empty unless exactly
     * one candidate matches: a partial refund on a merchant the user buys from
     * weekly is genuinely ambiguous, and guessing would attach the credit to
     * the wrong purchase.
     */
    public Optional<CanonicalTransaction> findRefundOriginal(CanonicalTransaction refund) {
        if (refund.getMerchant() == null) {
            return Optional.empty();
        }
        List<CanonicalTransaction> candidates = transactions.findRefundCandidates(
                refund.getAccountId(),
                refund.getMerchant(),
                refund.getOccurredOn().minusDays(120),
                refund.getOccurredOn());

        List<CanonicalTransaction> plausible = candidates.stream()
                .filter(c -> c.getAmountMinor() >= refund.getAmountMinor())
                .filter(c -> transactions.findByRefundOfId(c.getId()).isEmpty())
                .toList();

        // An exact amount match is strong enough to act on even with several
        // candidates: take the most recent exact match.
        List<CanonicalTransaction> exact = plausible.stream()
                .filter(c -> c.getAmountMinor() == refund.getAmountMinor())
                .toList();
        if (!exact.isEmpty()) {
            return Optional.of(exact.get(0));
        }
        return plausible.size() == 1 ? Optional.of(plausible.get(0)) : Optional.empty();
    }

    /**
     * Updates a pending transaction with the details of its settlement.
     *
     * <p>The posted amount wins, because an authorisation is provisional. This
     * is the pending-to-posted path that stops a hotel hold and its final
     * charge from becoming two expenses.
     */
    @Transactional
    public CanonicalTransaction settlePending(CanonicalTransaction pending,
                                              Money postedAmount,
                                              LocalDate postedOn,
                                              String reference) {
        pending.setAmount(postedAmount);
        pending.setStatus(TransactionStatus.POSTED);
        pending.setPostedOn(postedOn);
        if (reference != null && pending.getExternalReference() == null) {
            pending.setExternalReference(reference);
        }
        pending.setReconciliationStatus(ReconciliationStatus.MATCHED);
        log.debug("Settled pending transaction {}", pending.getId());
        return transactions.save(pending);
    }

    /**
     * Deletes a transaction and detaches its source records.
     *
     * <p>The source records survive. They are the evidence that the underlying
     * messages existed, and keeping them means the deleted transaction shows up
     * as unlinked evidence rather than vanishing without trace. They are
     * returned to {@code PENDING_PARSE} so a later sync can re-match them.
     */
    @Transactional
    public void delete(String id) {
        CanonicalTransaction txn = require(id);
        for (SourceRecord record : sourceRecords.findByCanonicalTransactionId(id)) {
            record.setCanonicalTransactionId(null);
            record.setStatus(com.ledger.source.SourceRecordStatus.PENDING_PARSE);
            sourceRecords.save(record);
        }
        transactions.delete(txn);
    }

    // --- Reads --------------------------------------------------------------

    public CanonicalTransaction require(String id) {
        return transactions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown transaction: " + id));
    }

    public Optional<CanonicalTransaction> find(String id) {
        return transactions.findById(id);
    }

    /** The source records that evidence a transaction. */
    public List<SourceRecord> evidenceFor(String transactionId) {
        return sourceRecords.findByCanonicalTransactionId(transactionId);
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * A transfer or card payment is meaningless without both sides, and the
     * schema enforces it. Failing here gives a clear message instead of a
     * constraint violation.
     */
    private void validateCounterAccount(TransactionType type, String accountId, String counterAccountId) {
        if (type == null) {
            return;
        }
        if (type.requiresCounterAccount() && counterAccountId == null) {
            throw new IllegalArgumentException(
                    type + " requires a counter account naming the other side of the movement");
        }
        if (counterAccountId != null && counterAccountId.equals(accountId)) {
            throw new IllegalArgumentException("a transaction cannot move money to the same account");
        }
        if (counterAccountId != null) {
            accountService.require(counterAccountId);
        }
        if (accountId != null) {
            accountService.require(accountId);
        }
    }

    private String buildDescription(SourceRecord record, CanonicalTransaction txn) {
        return TransactionDescriptions.describe(txn);
    }

    private String describeForReview(CanonicalTransaction txn) {
        StringBuilder sb = new StringBuilder();
        sb.append(txn.amount());
        if (txn.getMerchant() != null) {
            sb.append(" at ").append(txn.getMerchant());
        }
        sb.append(" on ").append(txn.getOccurredOn());
        return sb.toString();
    }
}
