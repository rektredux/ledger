package com.ledger.txn;

import com.ledger.account.Account;
import com.ledger.account.AccountRepository;
import com.ledger.account.AccountService;
import com.ledger.account.AccountType;
import com.ledger.money.Money;
import com.ledger.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accounting rules of section 5, asserted against the real database.
 *
 * <p>These are the tests that stop the ledger from lying. Each one encodes a
 * specific way a personal finance app commonly inflates or deflates the
 * numbers, and asserts this one does not.
 */
class AccountingRulesTest extends AbstractIntegrationTest {

    @Autowired
    private CanonicalTransactionRepository transactions;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountService accountService;

    private Account bank;
    private Account card;
    private Account savings;
    private Account cash;

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_31 = LocalDate.of(2026, 3, 31);
    private static final LocalDate MARCH_10 = LocalDate.of(2026, 3, 10);

    @BeforeEach
    void setUpAccounts() {
        bank = accounts.save(account("HDFC Savings", AccountType.BANK, 10_000_000L));
        savings = accounts.save(account("ICICI Savings", AccountType.BANK, 5_000_000L));
        cash = accounts.save(account("Wallet", AccountType.CASH, 200_000L));

        card = new Account("HDFC Card", AccountType.CREDIT_CARD, "INR");
        card.setCreditLimitMinor(20_000_000L);
        card.setStatementDay(15);
        card.setDueDay(3);
        card = accounts.save(card);
    }

    private static Account account(String name, AccountType type, long openingMinor) {
        Account account = new Account(name, type, "INR");
        account.setOpeningBalanceMinor(openingMinor);
        return account;
    }

    private Money inr(long rupees) {
        return Money.ofMinor(rupees * 100, "INR");
    }

    // --- Credit cards -------------------------------------------------------

    @Test
    @DisplayName("a credit card purchase is an expense and increases the card's outstanding balance")
    void cardPurchaseIsExpense() {
        transactionService.createManual(inr(2_000), MARCH_10, TransactionType.EXPENSE,
                card.getId(), null, "Croma", "shopping.electronics", null);

        assertThat(transactions.sumSpendingMinor(MARCH_1, MARCH_31)).isEqualTo(200_000L);
        assertThat(transactions.cardOutstandingMinor(card.getId()))
                .as("a purchase on the card increases what is owed")
                .isEqualTo(200_000L);
    }

    @Test
    @DisplayName("paying the credit card bill is NOT a second expense")
    void cardBillPaymentIsNotAnExpense() {
        // This is the single most important rule in the application. Spending
        // ₹2,000 on a card and then paying the ₹2,000 bill is ₹2,000 of
        // spending, not ₹4,000.
        transactionService.createManual(inr(2_000), MARCH_10, TransactionType.EXPENSE,
                card.getId(), null, "Croma", "shopping.electronics", null);
        transactionService.createManual(inr(2_000), MARCH_10.plusDays(20), TransactionType.CARD_PAYMENT,
                bank.getId(), card.getId(), null, null, "cleared the card");

        assertThat(transactions.sumSpendingMinor(MARCH_1, MARCH_31))
                .as("total spending must count the purchase once and the bill payment not at all")
                .isEqualTo(200_000L);

        assertThat(transactions.cardOutstandingMinor(card.getId()))
                .as("the bill payment settles the liability")
                .isZero();

        assertThat(transactions.sumIncomeMinor(MARCH_1, MARCH_31))
                .as("a card payment is not income to the card either")
                .isZero();

        // The money really did leave the bank account.
        AccountService.AccountBalance bankBalance = accountService.balanceOf(
                accounts.findById(bank.getId()).orElseThrow());
        assertThat(bankBalance.balance().minor())
                .as("the funding account is debited by the bill payment")
                .isEqualTo(10_000_000L - 200_000L);
    }

    @Test
    @DisplayName("a refund on a card reduces both spending and the outstanding balance")
    void cardRefundReducesOutstanding() {
        CanonicalTransaction purchase = transactionService.createManual(
                inr(3_000), MARCH_10, TransactionType.EXPENSE,
                card.getId(), null, "Myntra", "shopping.clothing", null);
        CanonicalTransaction refund = transactionService.createManual(
                inr(1_000), MARCH_10.plusDays(5), TransactionType.REFUND,
                card.getId(), null, "Myntra", "shopping.clothing", null);
        transactionService.linkRefund(refund.getId(), purchase.getId());

        assertThat(transactions.sumSpendingMinor(MARCH_1, MARCH_31))
                .as("gross spending is unchanged by a refund")
                .isEqualTo(300_000L);
        assertThat(transactions.sumSpendingOffsetsMinor(MARCH_1, MARCH_31))
                .as("the refund is reported as an offset, not as income")
                .isEqualTo(100_000L);
        assertThat(transactions.sumIncomeMinor(MARCH_1, MARCH_31))
                .as("a refund is never income")
                .isZero();

        Money net = LedgerMath.netSpending(
                Money.ofMinor(transactions.sumSpendingMinor(MARCH_1, MARCH_31), "INR"),
                Money.ofMinor(transactions.sumSpendingOffsetsMinor(MARCH_1, MARCH_31), "INR"));
        assertThat(net.minor()).isEqualTo(200_000L);

        assertThat(transactions.cardOutstandingMinor(card.getId())).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("a reversal cancels the original transaction out")
    void reversalCancelsOriginal() {
        CanonicalTransaction failed = transactionService.createManual(
                inr(1_500), MARCH_10, TransactionType.EXPENSE,
                bank.getId(), null, "Some Merchant", "shopping.online", null);
        CanonicalTransaction reversal = transactionService.createManual(
                inr(1_500), MARCH_10, TransactionType.REVERSAL,
                bank.getId(), null, "Some Merchant", "shopping.online", null);
        transactionService.linkRefund(reversal.getId(), failed.getId());

        long gross = transactions.sumSpendingMinor(MARCH_1, MARCH_31);
        long offsets = transactions.sumSpendingOffsetsMinor(MARCH_1, MARCH_31);
        assertThat(LedgerMath.netSpending(Money.ofMinor(gross, "INR"), Money.ofMinor(offsets, "INR")).minor())
                .as("a failed transaction that was reversed nets to zero spending")
                .isZero();

        AccountService.AccountBalance balance = accountService.balanceOf(
                accounts.findById(bank.getId()).orElseThrow());
        assertThat(balance.balance().minor())
                .as("the account balance is restored by the reversal")
                .isEqualTo(10_000_000L);
    }

    // --- Transfers ----------------------------------------------------------

    @Test
    @DisplayName("a transfer between owned accounts inflates neither spending nor income")
    void transferIsNeutral() {
        transactionService.createManual(inr(25_000), MARCH_10, TransactionType.TRANSFER,
                bank.getId(), savings.getId(), null, null, "moved to savings");

        assertThat(transactions.sumSpendingMinor(MARCH_1, MARCH_31))
                .as("moving your own money is not spending")
                .isZero();
        assertThat(transactions.sumIncomeMinor(MARCH_1, MARCH_31))
                .as("moving your own money is not income")
                .isZero();

        // But both balances move, in opposite directions and by the same amount.
        long fromBalance = accountService.balanceOf(
                accounts.findById(bank.getId()).orElseThrow()).balance().minor();
        long toBalance = accountService.balanceOf(
                accounts.findById(savings.getId()).orElseThrow()).balance().minor();

        assertThat(fromBalance).isEqualTo(10_000_000L - 2_500_000L);
        assertThat(toBalance).isEqualTo(5_000_000L + 2_500_000L);
        assertThat(fromBalance + toBalance)
                .as("a transfer conserves total net worth")
                .isEqualTo(10_000_000L + 5_000_000L);
    }

    @Test
    @DisplayName("a cash withdrawal is excluded from spending by its category")
    void cashWithdrawalExcludedFromSpending() {
        // Typed as an EXPENSE because that is what the bank reports, but filed
        // under the non-spending transfer.cash category. See
        // TransactionTextExtractor.CASH_WITHDRAWAL_WORDS for why.
        transactionService.createManual(inr(5_000), MARCH_10, TransactionType.EXPENSE,
                bank.getId(), null, null, "transfer.cash", null);

        assertThat(transactions.sumSpendingMinor(MARCH_1, MARCH_31))
                .as("taking cash out is not itself consumption")
                .isZero();

        assertThat(accountService.balanceOf(accounts.findById(bank.getId()).orElseThrow())
                .balance().minor())
                .as("the money did leave the bank account")
                .isEqualTo(10_000_000L - 500_000L);
    }

    @Test
    @DisplayName("an investment purchase is excluded from spending")
    void investmentExcludedFromSpending() {
        transactionService.createManual(inr(50_000), MARCH_10, TransactionType.EXPENSE,
                bank.getId(), null, "Zerodha", "investment.other", null);

        assertThat(transactions.sumSpendingMinor(MARCH_1, MARCH_31))
                .as("buying an asset is not an expense")
                .isZero();
    }

    // --- Totals -------------------------------------------------------------

    @Test
    @DisplayName("income, spending and net cash flow are computed consistently")
    void cashFlowIsConsistent() {
        transactionService.createManual(inr(1_50_000), MARCH_1, TransactionType.INCOME,
                bank.getId(), null, "Acme Technologies", "income.salary", null);
        transactionService.createManual(inr(30_000), MARCH_10, TransactionType.EXPENSE,
                bank.getId(), null, "Rent", "housing.rent", null);
        transactionService.createManual(inr(8_000), MARCH_10, TransactionType.EXPENSE,
                card.getId(), null, "BigBasket", "food.groceries", null);
        // Neither of these should touch the totals.
        transactionService.createManual(inr(20_000), MARCH_10, TransactionType.TRANSFER,
                bank.getId(), savings.getId(), null, null, null);
        transactionService.createManual(inr(8_000), MARCH_10.plusDays(15), TransactionType.CARD_PAYMENT,
                bank.getId(), card.getId(), null, null, null);

        Money income = Money.ofMinor(transactions.sumIncomeMinor(MARCH_1, MARCH_31), "INR");
        Money gross = Money.ofMinor(transactions.sumSpendingMinor(MARCH_1, MARCH_31), "INR");
        Money offsets = Money.ofMinor(transactions.sumSpendingOffsetsMinor(MARCH_1, MARCH_31), "INR");
        Money net = LedgerMath.netSpending(gross, offsets);

        assertThat(income.minor()).isEqualTo(15_000_000L);
        assertThat(net.minor())
                .as("only the rent and the groceries are spending")
                .isEqualTo(3_800_000L);
        assertThat(LedgerMath.netCashFlow(income, net).minor()).isEqualTo(11_200_000L);
    }

    @Test
    @DisplayName("a void transaction is excluded from every total")
    void voidIsExcluded() {
        CanonicalTransaction txn = transactionService.createManual(
                inr(1_000), MARCH_10, TransactionType.EXPENSE,
                bank.getId(), null, "Expired Hold", "shopping.online", null);
        txn.setStatus(TransactionStatus.VOID);
        transactions.save(txn);

        assertThat(transactions.sumSpendingMinor(MARCH_1, MARCH_31)).isZero();
        assertThat(transactions.netMovementMinor(bank.getId())).isZero();
    }

    // --- Date boundaries ----------------------------------------------------

    @Test
    @DisplayName("month boundaries are exact: nothing leaks between adjacent months")
    void monthBoundariesAreExact() {
        transactionService.createManual(inr(100), LocalDate.of(2026, 2, 28), TransactionType.EXPENSE,
                bank.getId(), null, "February", "food.cafe", null);
        transactionService.createManual(inr(200), LocalDate.of(2026, 3, 1), TransactionType.EXPENSE,
                bank.getId(), null, "March first", "food.cafe", null);
        transactionService.createManual(inr(400), LocalDate.of(2026, 3, 31), TransactionType.EXPENSE,
                bank.getId(), null, "March last", "food.cafe", null);
        transactionService.createManual(inr(800), LocalDate.of(2026, 4, 1), TransactionType.EXPENSE,
                bank.getId(), null, "April", "food.cafe", null);

        assertThat(transactions.sumSpendingMinor(MARCH_1, MARCH_31))
                .as("March must contain exactly the 1st and the 31st")
                .isEqualTo(60_000L);
        assertThat(transactions.sumSpendingMinor(
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28))).isEqualTo(10_000L);
        assertThat(transactions.sumSpendingMinor(
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))).isEqualTo(80_000L);
    }

    // --- Credit card statement cycle ---------------------------------------

    @Test
    @DisplayName("statement period spending covers the configured cycle, not the calendar month")
    void statementCycleSpending() {
        // Card statements on the 15th, so the period running up to 15 March
        // starts on 16 February.
        transactionService.createManual(inr(1_000), LocalDate.of(2026, 2, 20), TransactionType.EXPENSE,
                card.getId(), null, "In cycle", "food.cafe", null);
        transactionService.createManual(inr(2_000), LocalDate.of(2026, 3, 14), TransactionType.EXPENSE,
                card.getId(), null, "In cycle", "food.cafe", null);
        transactionService.createManual(inr(4_000), LocalDate.of(2026, 3, 16), TransactionType.EXPENSE,
                card.getId(), null, "Next cycle", "food.cafe", null);

        Account reloaded = accounts.findById(card.getId()).orElseThrow();
        AccountService.CreditCardSummary summary =
                accountService.cardSummary(reloaded, LocalDate.of(2026, 3, 10));

        assertThat(summary.currentCycle()).isNotNull();
        assertThat(summary.currentCycle().from()).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(summary.currentCycle().to()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(summary.currentCycleSpending().minor())
                .as("the 20 Feb and 14 Mar purchases are in this cycle; the 16 Mar one is not")
                .isEqualTo(300_000L);

        assertThat(summary.outstanding().minor())
                .as("outstanding covers everything charged, regardless of cycle")
                .isEqualTo(700_000L);
        assertThat(summary.availableCredit().minor()).isEqualTo(20_000_000L - 700_000L);
        assertThat(summary.utilisationPercent()).isEqualTo(3);
    }

    @Test
    @DisplayName("an unconfigured credit limit reports as unknown rather than zero")
    void unconfiguredLimitIsNull() {
        Account plain = accounts.save(new Account("Unknown Limit Card", AccountType.CREDIT_CARD, "INR"));
        AccountService.CreditCardSummary summary = accountService.cardSummary(plain, MARCH_10);

        assertThat(summary.creditLimit())
                .as("a zero limit would read as 'no credit available', which is a claim we cannot make")
                .isNull();
        assertThat(summary.availableCredit()).isNull();
        assertThat(summary.utilisationPercent()).isNull();
        assertThat(summary.cycleConfigured()).isFalse();
    }

    // --- Balance honesty ----------------------------------------------------

    @Test
    @DisplayName("a cash account's balance is reported as manual-only")
    void cashBalanceIsMarkedManual() {
        AccountService.AccountBalance balance = accountService.balanceOf(
                accounts.findById(cash.getId()).orElseThrow());
        assertThat(balance.confidence())
                .as("no provider can observe cash, and the UI must say so")
                .isEqualTo(AccountService.BalanceConfidence.MANUAL_ONLY);
    }

    @Test
    @DisplayName("a bank account with no ingested transactions is marked as having no observations")
    void emptyBankBalanceIsMarked() {
        AccountService.AccountBalance balance = accountService.balanceOf(
                accounts.findById(savings.getId()).orElseThrow());
        assertThat(balance.confidence())
                .isEqualTo(AccountService.BalanceConfidence.NO_OBSERVATIONS);
        assertThat(balance.balance().minor()).isEqualTo(5_000_000L);
    }

    // --- Validation ---------------------------------------------------------

    @Test
    @DisplayName("a transfer without a counter account is rejected, not silently stored")
    void transferRequiresCounterAccount() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                transactionService.createManual(inr(1_000), MARCH_10, TransactionType.TRANSFER,
                        bank.getId(), null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counter account");
    }

    @Test
    @DisplayName("a refund larger than its original is rejected")
    void refundCannotExceedOriginal() {
        CanonicalTransaction purchase = transactionService.createManual(
                inr(500), MARCH_10, TransactionType.EXPENSE,
                bank.getId(), null, "Shop", "shopping.online", null);
        CanonicalTransaction refund = transactionService.createManual(
                inr(900), MARCH_10, TransactionType.REFUND,
                bank.getId(), null, "Shop", "shopping.online", null);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                transactionService.linkRefund(refund.getId(), purchase.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }
}
