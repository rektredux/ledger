package com.ledger.ingest;

import com.ledger.account.Account;
import com.ledger.account.AccountRepository;
import com.ledger.account.AccountType;
import com.ledger.review.ReviewItem;
import com.ledger.review.ReviewService;
import com.ledger.source.ProviderType;
import com.ledger.source.SourceRecord;
import com.ledger.source.SourceRecordRepository;
import com.ledger.source.SourceRecordStatus;
import com.ledger.support.AbstractIntegrationTest;
import com.ledger.support.MessageFixtures;
import com.ledger.txn.CanonicalTransaction;
import com.ledger.txn.CanonicalTransactionRepository;
import com.ledger.txn.ReconciliationStatus;
import com.ledger.txn.TransactionStatus;
import com.ledger.txn.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the ingestion pipeline: parse, attribute, deduplicate,
 * store.
 *
 * <p>This is the suite that protects the application's central promise — that
 * a purchase observed several times becomes one expense, and that repeated
 * synchronization does not inflate the ledger.
 */
class IngestionPipelineTest extends AbstractIntegrationTest {

    @Autowired
    private IngestionService ingestion;

    @Autowired
    private CanonicalTransactionRepository transactions;

    @Autowired
    private SourceRecordRepository sourceRecords;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private MatchDecisionRepository decisions;

    @Autowired
    private ReviewService reviewService;

    private Account card;
    private Account bank;

    private static final LocalDate MARCH_15 = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void createAccounts() {
        card = new Account("HDFC Credit Card", AccountType.CREDIT_CARD, "INR");
        card.setInstitution("HDFC Bank");
        card.setInstrumentHint("4567");
        card.setCreditLimitMinor(20_000_000L);
        card.setStatementDay(15);
        card.setDueDay(3);
        card = accounts.save(card);

        bank = new Account("HDFC Savings", AccountType.BANK, "INR");
        bank.setInstitution("HDFC Bank");
        bank.setInstrumentHint("1234");
        bank.setOpeningBalanceMinor(10_000_000L);
        bank = accounts.save(bank);
    }

    private IngestionService.Result ingest(com.ledger.ingest.RawObservation observation) {
        return ingestion.ingest(ProviderType.SIMULATED, null, observation, null);
    }

    // --- Basic ingestion ---------------------------------------------------

    @Test
    @DisplayName("a card debit alert becomes one expense attributed to the card")
    void cardAlertCreatesExpense() {
        IngestionService.Result result = ingest(MessageFixtures.cardDebitAlert(
                "msg-1", "450.00", "SWIGGY", MARCH_15, "4567", "TXN1234567890"));

        assertThat(result).isEqualTo(IngestionService.Result.CREATED);

        List<CanonicalTransaction> all = transactions.findAll();
        assertThat(all).hasSize(1);

        CanonicalTransaction txn = all.get(0);
        assertThat(txn.getAmountMinor()).isEqualTo(45_000L);
        assertThat(txn.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(txn.getOccurredOn()).isEqualTo(MARCH_15);
        assertThat(txn.getAccountId())
                .as("the card tail 4567 must resolve to the configured card")
                .isEqualTo(card.getId());
        assertThat(txn.getMerchant()).isEqualTo("Swiggy");
        assertThat(txn.getExternalReference()).isEqualTo("TXN1234567890");
        assertThat(txn.getCategoryId())
                .as("Swiggy should be categorised automatically with no user input")
                .isEqualTo("food.delivery");
    }

    @Test
    @DisplayName("a salary credit becomes income, not a reduction in spending")
    void salaryBecomesIncome() {
        ingest(MessageFixtures.salaryCredit("msg-2", "1,50,000.00", MARCH_15, "1234"));

        CanonicalTransaction txn = transactions.findAll().get(0);
        assertThat(txn.getType()).isEqualTo(TransactionType.INCOME);
        assertThat(txn.getAmountMinor()).isEqualTo(15_000_000L);
        assertThat(transactions.sumIncomeMinor(MARCH_15, MARCH_15)).isEqualTo(15_000_000L);
        assertThat(transactions.sumSpendingMinor(MARCH_15, MARCH_15)).isZero();
    }

    // --- Idempotency -------------------------------------------------------

    @Test
    @DisplayName("re-ingesting the same message id does not create a second transaction")
    void repeatedIngestionIsIdempotent() {
        var message = MessageFixtures.cardDebitAlert(
                "msg-dup", "450.00", "SWIGGY", MARCH_15, "4567", "TXN1234567890");

        assertThat(ingest(message)).isEqualTo(IngestionService.Result.CREATED);
        assertThat(ingest(message)).isEqualTo(IngestionService.Result.ALREADY_SEEN);
        assertThat(ingest(message)).isEqualTo(IngestionService.Result.ALREADY_SEEN);

        assertThat(transactions.count()).isEqualTo(1);
        assertThat(sourceRecords.count())
                .as("the provider id is unique, so only one source record exists")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the same alert delivered under a new message id is recognised by content")
    void identicalContentUnderDifferentIdIsNotDuplicated() {
        // A forwarded copy, or the same alert re-delivered with a new id. The
        // provider-id check cannot catch this; the content hash can.
        ingest(MessageFixtures.cardDebitAlert(
                "msg-a", "450.00", "SWIGGY", MARCH_15, "4567", "TXN1234567890"));
        IngestionService.Result second = ingest(MessageFixtures.cardDebitAlert(
                "msg-b", "450.00", "SWIGGY", MARCH_15, "4567", "TXN1234567890"));

        assertThat(second).isEqualTo(IngestionService.Result.ALREADY_SEEN);
        assertThat(transactions.count()).isEqualTo(1);
    }

    // --- Multiple sources, one event ---------------------------------------

    @Test
    @DisplayName("a merchant receipt and a bank alert for one purchase become one expense")
    void twoSourcesOneTransaction() {
        // The merchant receipt arrives first and names the merchant cleanly but
        // cannot say which card was charged.
        ingest(MessageFixtures.merchantReceipt(
                "receipt-1", "450.00", MARCH_15, "swiggy.in", "SW12345678"));

        // The bank alert for the same purchase arrives later with the card tail.
        IngestionService.Result second = ingest(MessageFixtures.upiDebitAlert(
                "alert-1", "450.00", "SWIGGY", MARCH_15, "1234", "UPI987654321098"));

        assertThat(second)
                .as("the second observation must link, not create")
                .isEqualTo(IngestionService.Result.LINKED);
        assertThat(transactions.count()).isEqualTo(1);

        CanonicalTransaction txn = transactions.findAll().get(0);
        assertThat(txn.getAmountMinor()).isEqualTo(45_000L);
        assertThat(txn.getReconciliationStatus())
                .as("two independent sightings corroborate the transaction")
                .isEqualTo(ReconciliationStatus.MATCHED);
        assertThat(txn.getAccountId())
                .as("the account from the bank alert should enrich the receipt-created transaction")
                .isEqualTo(bank.getId());

        // Both observations survive as evidence, linked to the one event.
        List<SourceRecord> evidence = sourceRecords.findByCanonicalTransactionId(txn.getId());
        assertThat(evidence).hasSize(2);
        assertThat(evidence).allMatch(r -> r.getStatus() == SourceRecordStatus.LINKED);

        // Spending counts the purchase exactly once.
        assertThat(transactions.sumSpendingMinor(MARCH_15, MARCH_15)).isEqualTo(45_000L);
    }

    @Test
    @DisplayName("a matching reference links two observations with certainty")
    void strongReferenceLinks() {
        ingest(MessageFixtures.upiDebitAlert(
                "alert-x", "1,250.00", "BIGBASKET", MARCH_15, "1234", "UPI555666777888"));
        // Same reference, different wording and a day later: the reference
        // settles it regardless.
        ingest(MessageFixtures.upiDebitAlert(
                "alert-y", "1,250.00", "BIG BASKET", MARCH_15.plusDays(1), "1234", "UPI555666777888"));

        assertThat(transactions.count()).isEqualTo(1);
        assertThat(decisions.findAll())
                .anyMatch(d -> d.getStrategy() == MatchDecision.Strategy.STRONG_REFERENCE);
    }

    // --- Never merge on amount and date alone ------------------------------

    @Test
    @DisplayName("two genuinely different merchants at the same amount and date stay separate")
    void differentMerchantsAreNotMerged() {
        ingest(MessageFixtures.cardDebitAlert(
                "m-1", "500.00", "SWIGGY", MARCH_15, "4567", "TXN111111111111"));
        ingest(MessageFixtures.cardDebitAlert(
                "m-2", "500.00", "NETFLIX", MARCH_15, "4567", "TXN222222222222"));

        assertThat(transactions.count())
                .as("same amount, same day, same card, but different merchants and references")
                .isEqualTo(2);
        assertThat(transactions.sumSpendingMinor(MARCH_15, MARCH_15)).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("an expense and a refund of the same amount are never merged")
    void expenseAndRefundNotMerged() {
        ingest(MessageFixtures.cardDebitAlert(
                "e-1", "999.00", "MYNTRA", MARCH_15, "4567", "TXN333333333333"));
        ingest(MessageFixtures.refundAlert(
                "r-1", "999.00", "MYNTRA", MARCH_15, "1234", "TXN444444444444"));

        assertThat(transactions.count()).isEqualTo(2);
        assertThat(transactions.findAll())
                .extracting(CanonicalTransaction::getType)
                .containsExactlyInAnyOrder(TransactionType.EXPENSE, TransactionType.REFUND);
    }

    @Test
    @DisplayName("an identical repeat purchase is queued for review, not silently merged")
    void ambiguousRepeatPurchaseGoesToReview() {
        // Two coffees at the same shop, same amount, same day, and crucially no
        // reference to tell them apart. This is genuinely ambiguous: it could
        // be one purchase reported twice, or two real purchases. The matcher
        // must not decide on its own.
        var first = com.ledger.ingest.RawObservation.of("amb-1", "alerts@hdfcbank.net",
                "Transaction alert",
                MARCH_15.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant(),
                "Rs. 250.00 has been debited from your Credit Card ending 4567 "
                        + "on 15-03-2026 at STARBUCKS.");
        var second = com.ledger.ingest.RawObservation.of("amb-2", "alerts@hdfcbank.net",
                "Transaction alert",
                MARCH_15.atTime(17, 0).atZone(java.time.ZoneId.systemDefault()).toInstant(),
                "Rs. 250.00 has been debited from your Credit Card ending 4567 "
                        + "on 15-03-2026 at STARBUCKS COFFEE.");

        assertThat(ingest(first)).isEqualTo(IngestionService.Result.CREATED);
        IngestionService.Result outcome = ingest(second);

        assertThat(outcome)
                .as("an ambiguous near-duplicate must be queued, never merged or dropped")
                .isEqualTo(IngestionService.Result.QUEUED_FOR_REVIEW);

        // Nothing was merged and nothing was lost.
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(sourceRecords.count()).isEqualTo(2);
        assertThat(sourceRecords.findByProviderAndProviderRecordId(ProviderType.SIMULATED, "amb-2"))
                .get()
                .extracting(SourceRecord::getStatus)
                .isEqualTo(SourceRecordStatus.NEEDS_REVIEW);

        // The question is visible, with the reasoning attached.
        assertThat(reviewService.openCountsByKind().get(ReviewItem.Kind.AMBIGUOUS_MATCH))
                .isEqualTo(1L);
        assertThat(decisions.findAll())
                .anyMatch(d -> d.getDecision() == MatchDecision.Decision.QUEUED_FOR_REVIEW
                        && d.getRationale() != null && !d.getRationale().isBlank());
    }

    // --- Pending to posted -------------------------------------------------

    @Test
    @DisplayName("a posted amount settles the pending authorisation instead of adding a second expense")
    void pendingBecomesPosted() {
        ingest(MessageFixtures.pendingAuthorisation(
                "auth-1", "5,000.00", "TAJ HOTELS", MARCH_15, "4567"));

        CanonicalTransaction pending = transactions.findAll().get(0);
        assertThat(pending.getStatus()).isEqualTo(TransactionStatus.PENDING);

        // The final charge posts for a slightly different amount, as holds do.
        IngestionService.Result settled = ingest(MessageFixtures.cardDebitAlert(
                "post-1", "5,450.00", "TAJ HOTELS", MARCH_15.plusDays(2), "4567", "TXN999888777666"));

        assertThat(settled).isEqualTo(IngestionService.Result.SETTLED);
        assertThat(transactions.count())
                .as("a hold and its settlement are one expense, not two")
                .isEqualTo(1);

        CanonicalTransaction posted = transactions.findById(pending.getId()).orElseThrow();
        assertThat(posted.getStatus()).isEqualTo(TransactionStatus.POSTED);
        assertThat(posted.getAmountMinor())
                .as("the posted amount supersedes the authorisation amount")
                .isEqualTo(545_000L);
        assertThat(posted.getPostedOn()).isEqualTo(MARCH_15.plusDays(2));
    }

    // --- Non-transactional mail --------------------------------------------

    @Test
    @DisplayName("statement notices, OTPs and promotions never become transactions")
    void nonTransactionalMailIsIgnored() {
        assertThat(ingest(MessageFixtures.statementNotice("st-1", MARCH_15, "4567")))
                .isEqualTo(IngestionService.Result.IGNORED);
        assertThat(ingest(MessageFixtures.otpMessage("otp-1", MARCH_15)))
                .isEqualTo(IngestionService.Result.IGNORED);
        assertThat(ingest(MessageFixtures.promotionalOffer("promo-1", MARCH_15)))
                .isEqualTo(IngestionService.Result.IGNORED);
        assertThat(ingest(MessageFixtures.merchantMarketing("mk-1", MARCH_15, "swiggy.in")))
                .isEqualTo(IngestionService.Result.IGNORED);

        assertThat(transactions.count())
                .as("none of these describe money moving")
                .isZero();
        // All four are recorded so they are not re-examined on every sync, and
        // so the user can see what was filtered.
        assertThat(sourceRecords.count()).isEqualTo(4);
        assertThat(sourceRecords.findAll())
                .allMatch(r -> r.getStatus() == SourceRecordStatus.IGNORED)
                .allMatch(r -> r.getParseError() != null);
    }

    // --- Privacy ------------------------------------------------------------

    @Test
    @DisplayName("message bodies are not persisted and excerpts are redacted")
    void bodiesAreNotStored() {
        String reference = "TXN1234567890";
        ingest(MessageFixtures.cardDebitAlert(
                "priv-1", "450.00", "SWIGGY", MARCH_15, "4567", reference));

        SourceRecord record = sourceRecords.findAll().get(0);

        // A hash is kept for duplicate detection, but not the content itself.
        assertThat(record.getContentHash()).isNotNull().hasSize(64);
        assertThat(record.getRedactedExcerpt())
                .isNotNull()
                .as("the excerpt is capped, not a full body copy")
                .hasSizeLessThanOrEqualTo(com.ledger.common.Redaction.EXCERPT_LIMIT);

        // The excerpt deliberately retains amounts and merchant names: it
        // exists so the user can see why a record parsed the way it did, and
        // those are the fields that explain it. What must not survive are
        // identifiers, which Redaction masks.
        assertThat(com.ledger.common.Redaction.redact("A/c number 123456789012"))
                .as("a long digit run is an identifier and must be masked")
                .doesNotContain("123456789012");
        assertThat(com.ledger.common.Redaction.redact("OTP is 483920"))
                .doesNotContain("483920");
        assertThat(com.ledger.common.Redaction.redact("card 4111 1111 1111 1111"))
                .doesNotContain("4111");
    }
}
