package com.ledger;

import com.ledger.account.Account;
import com.ledger.account.AccountRepository;
import com.ledger.account.AccountType;
import com.ledger.category.CategoryRepository;
import com.ledger.categorize.CategoryRuleRepository;
import com.ledger.money.Money;
import com.ledger.support.AbstractIntegrationTest;
import com.ledger.txn.CanonicalTransaction;
import com.ledger.txn.CanonicalTransactionRepository;
import com.ledger.txn.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the JPA entities, the Flyway schema and SQLite's actual
 * behaviour all agree.
 *
 * <p>The context starting at all is most of the assertion here: with
 * {@code ddl-auto=validate}, a column renamed in a migration but not in an
 * entity (or vice versa) prevents startup. The explicit tests below cover the
 * things validation cannot check — that values survive a write/read round trip
 * with the right precision, and that the seed migration actually ran.
 */
class SchemaMappingTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CanonicalTransactionRepository transactions;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private CategoryRuleRepository rules;

    @Test
    @DisplayName("seed migration populated the category tree and built-in rules")
    void seedDataIsPresent() {
        assertThat(categories.count())
                .as("seeded categories from V2")
                .isGreaterThan(40);
        assertThat(rules.findByEnabledTrueOrderByPriorityDesc())
                .as("built-in category rules from V2")
                .isNotEmpty();
        assertThat(categories.findById("food.groceries")).isPresent();
        assertThat(categories.findById("transfer.cardbill"))
                .get()
                .satisfies(c -> assertThat(c.isExcludedFromSpending())
                        .as("a credit card bill payment category must never count as spending")
                        .isTrue());
    }

    @Test
    @DisplayName("money round-trips through SQLite with exact paise precision")
    void moneyRoundTripsExactly() {
        Account account = accounts.save(bankAccount());

        // 1234.56 INR -> 123456 paise. A float would not hold this exactly.
        CanonicalTransaction txn = new CanonicalTransaction(
                Money.ofMinor(123_456L, "INR"), LocalDate.of(2026, 3, 15), TransactionType.EXPENSE);
        txn.setAccountId(account.getId());
        txn.setMerchant("Test Merchant");
        String id = transactions.save(txn).getId();

        CanonicalTransaction loaded = transactions.findById(id).orElseThrow();
        assertThat(loaded.getAmountMinor()).isEqualTo(123_456L);
        assertThat(loaded.amount().toMajor().toPlainString()).isEqualTo("1234.56");
        assertThat(loaded.getCurrency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("dates and times round-trip, and date range queries are exact")
    void temporalValuesRoundTrip() {
        Account account = accounts.save(bankAccount());

        CanonicalTransaction txn = new CanonicalTransaction(
                Money.ofMinor(5_000L, "INR"), LocalDate.of(2026, 1, 31), TransactionType.EXPENSE);
        txn.setAccountId(account.getId());
        txn.setOccurredAtTime(LocalTime.of(23, 59, 58));
        transactions.save(txn);

        CanonicalTransaction loaded = transactions.findById(txn.getId()).orElseThrow();
        assertThat(loaded.getOccurredOn()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(loaded.getOccurredAtTime()).isEqualTo(LocalTime.of(23, 59, 58));
        assertThat(loaded.getCreatedAt()).isNotNull();

        // The ISO-8601 text storage format must make BETWEEN exact at the
        // month boundary, which is the whole reason for choosing it.
        assertThat(transactions.sumSpendingMinor(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .isEqualTo(5_000L);
        assertThat(transactions.sumSpendingMinor(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 30)))
                .as("a transaction on the 31st must fall outside a range ending on the 30th")
                .isZero();
        assertThat(transactions.sumSpendingMinor(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
                .isZero();
    }

    @Test
    @DisplayName("aggregates over an empty dataset return zero, not null")
    void emptyDatasetAggregatesAreZero() {
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 5, 31);

        assertThat(transactions.sumSpendingMinor(from, to)).isZero();
        assertThat(transactions.sumIncomeMinor(from, to)).isZero();
        assertThat(transactions.sumSpendingOffsetsMinor(from, to)).isZero();
        assertThat(transactions.sumSpendingByCategory(from, to)).isEmpty();
        assertThat(transactions.sumSpendingByMerchant(from, to)).isEmpty();
        assertThat(transactions.findEarliestDate()).isNull();
    }

    @Test
    @DisplayName("an instrument hint resolves to its account regardless of masking")
    void instrumentHintLookup() {
        Account card = new Account("HDFC Card", AccountType.CREDIT_CARD, "INR");
        card.setInstitution("HDFC Bank");
        card.setInstrumentHint("4567");
        accounts.save(card);

        assertThat(accounts.findByInstrumentHintIgnoreCase("4567"))
                .extracting(Account::getId)
                .containsExactly(card.getId());
        assertThat(accounts.findByInstrumentHintIgnoreCase("9999")).isEmpty();
    }

    private static Account bankAccount() {
        Account account = new Account("Test Bank", AccountType.BANK, "INR");
        account.setInstitution("Test Institution");
        return account;
    }
}
