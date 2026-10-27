package com.ledger.account;

import com.ledger.money.Money;
import com.ledger.source.ProviderConnectionRepository;
import com.ledger.txn.CanonicalTransactionRepository;
import com.ledger.txn.LedgerMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Accounts, derived balances and credit card summaries.
 *
 * <h2>On the honesty of balances</h2>
 * Every balance this service returns is {@code openingBalance + observed
 * movement}. It is a correct sum of what the ledger knows, which is not the
 * same as the real balance at the bank. If a provider never saw a transaction,
 * it is not in the sum. Each result therefore carries a
 * {@link BalanceConfidence} describing what it is based on, and the UI presents
 * balances as derived figures rather than as authoritative ones.
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CanonicalTransactionRepository transactions;
    private final ProviderConnectionRepository connections;

    public AccountService(AccountRepository accounts,
                          CanonicalTransactionRepository transactions,
                          ProviderConnectionRepository connections) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.connections = connections;
    }

    /** How much weight a derived balance deserves. */
    public enum BalanceConfidence {
        /**
         * No automated source can observe this account at all (cash). The
         * balance is exactly what the user told us, plus what they entered.
         */
        MANUAL_ONLY,
        /**
         * An automated source is connected but nothing has been ingested for
         * this account yet, so the balance is just the opening figure.
         */
        NO_OBSERVATIONS,
        /**
         * Derived from ingested transactions. Complete only to the extent that
         * the connected sources observed everything.
         */
        DERIVED_FROM_INGESTION
    }

    public record AccountBalance(Account account,
                                 Money openingBalance,
                                 Money netMovement,
                                 Money balance,
                                 BalanceConfidence confidence,
                                 long transactionCount) {
    }

    public record CreditCardSummary(Account account,
                                    Money outstanding,
                                    Money creditLimit,
                                    Money availableCredit,
                                    Integer utilisationPercent,
                                    StatementCycle currentCycle,
                                    Money currentCycleSpending,
                                    StatementCycle previousCycle,
                                    Money previousCycleSpending,
                                    boolean cycleConfigured) {
    }

    // --- Reads --------------------------------------------------------------

    public List<Account> findActive() {
        return accounts.findByArchivedFalseOrderByNameAsc();
    }

    public List<Account> findAll() {
        return accounts.findAll();
    }

    public Optional<Account> find(String id) {
        return accounts.findById(id);
    }

    public Account require(String id) {
        return accounts.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown account: " + id));
    }

    /**
     * Resolves a parsed instrument hint to exactly one account.
     *
     * <p>Returns empty when the hint matches nothing <em>or</em> matches more
     * than one account. An ambiguous hint — two cards ending 4567 — must not be
     * resolved by picking one; the transaction is better left unattributed and
     * flagged than attributed to the wrong card.
     */
    public Optional<Account> resolveByInstrumentHint(String hint) {
        if (hint == null || hint.isBlank()) {
            return Optional.empty();
        }
        String normalised = normaliseHint(hint);
        if (normalised.isEmpty()) {
            return Optional.empty();
        }
        List<Account> matches = accounts.findByInstrumentHintIgnoreCase(normalised);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /**
     * Strips the masking banks put around an instrument tail so that
     * {@code "XXXX4567"}, {@code "xx 4567"} and {@code "**4567"} all reduce to
     * {@code "4567"}.
     */
    public static String normaliseHint(String hint) {
        if (hint == null) {
            return "";
        }
        String cleaned = hint.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9@.]", "");
        // A pure-masking prefix carries no information; keep the trailing digits.
        cleaned = cleaned.replaceAll("^[x*]+", "");
        return cleaned;
    }

    public AccountBalance balanceOf(Account account) {
        long movementMinor = transactions.netMovementMinor(account.getId());
        Money opening = account.openingBalance();
        Money movement = Money.ofMinor(movementMinor, account.getCurrency());
        Money balance = opening.plus(movement);

        BalanceConfidence confidence;
        if (account.isManualOnly()) {
            confidence = BalanceConfidence.MANUAL_ONLY;
        } else if (movementMinor == 0) {
            confidence = BalanceConfidence.NO_OBSERVATIONS;
        } else {
            confidence = BalanceConfidence.DERIVED_FROM_INGESTION;
        }

        return new AccountBalance(account, opening, movement, balance, confidence, 0L);
    }

    public List<AccountBalance> balances() {
        List<AccountBalance> result = new ArrayList<>();
        for (Account account : findActive()) {
            result.add(balanceOf(account));
        }
        return result;
    }

    /**
     * Credit card summary: outstanding liability, available credit and
     * statement-period spending.
     *
     * <p>{@code creditLimit}, {@code availableCredit} and
     * {@code utilisationPercent} are null when no limit is configured, and the
     * cycle fields are null when no statement day is configured. Null here
     * means "not known", and the UI renders it as such instead of showing a
     * zero that looks like a fact.
     */
    public CreditCardSummary cardSummary(Account card, LocalDate today) {
        if (card.getType() != AccountType.CREDIT_CARD) {
            throw new IllegalArgumentException("not a credit card: " + card.getId());
        }

        Money outstanding = Money.ofMinor(transactions.cardOutstandingMinor(card.getId()), card.getCurrency());
        Money limit = card.creditLimit();
        Money available = LedgerMath.availableCredit(card, outstanding);
        Integer utilisation = (limit == null || limit.minor() <= 0)
                ? null
                : LedgerMath.budgetUsedPercent(outstanding, limit);

        StatementCycle current = StatementCycle.current(card, today);
        StatementCycle previous = StatementCycle.previous(card, today);
        Money currentSpend = current == null
                ? null
                : Money.ofMinor(transactions.sumCardSpendingMinor(card.getId(), current.from(), current.to()),
                        card.getCurrency());
        Money previousSpend = previous == null
                ? null
                : Money.ofMinor(transactions.sumCardSpendingMinor(card.getId(), previous.from(), previous.to()),
                        card.getCurrency());

        return new CreditCardSummary(card, outstanding, limit, available, utilisation,
                current, currentSpend, previous, previousSpend, current != null);
    }

    public List<CreditCardSummary> cardSummaries(LocalDate today) {
        List<CreditCardSummary> result = new ArrayList<>();
        for (Account card : accounts.findByTypeAndArchivedFalse(AccountType.CREDIT_CARD)) {
            result.add(cardSummary(card, today));
        }
        return result;
    }

    // --- Writes -------------------------------------------------------------

    @Transactional
    public Account create(Account account) {
        validate(account);
        if (account.getInstrumentHint() != null) {
            account.setInstrumentHint(normaliseHint(account.getInstrumentHint()));
        }
        return accounts.save(account);
    }

    @Transactional
    public Account update(String id, Account changes) {
        Account existing = require(id);
        existing.setName(changes.getName());
        existing.setType(changes.getType());
        existing.setInstitution(changes.getInstitution());
        existing.setInstrumentHint(changes.getInstrumentHint() == null
                ? null : normaliseHint(changes.getInstrumentHint()));
        existing.setOpeningBalanceMinor(changes.getOpeningBalanceMinor());
        existing.setCreditLimitMinor(changes.getCreditLimitMinor());
        existing.setStatementDay(changes.getStatementDay());
        existing.setDueDay(changes.getDueDay());
        existing.setArchived(changes.isArchived());
        validate(existing);
        return accounts.save(existing);
    }

    /**
     * Archives an account rather than deleting it.
     *
     * <p>Deleting would detach every transaction that referenced it
     * ({@code ON DELETE SET NULL}), silently turning a year of attributed
     * spending into unattributed spending. Archiving keeps history intact and
     * removes the account from pickers.
     */
    @Transactional
    public Account archive(String id) {
        Account account = require(id);
        account.setArchived(true);
        return accounts.save(account);
    }

    private void validate(Account account) {
        if (account.getType() == null) {
            throw new IllegalArgumentException("account type is required");
        }
        if (!account.getType().hasBillingCycle()) {
            if (account.getStatementDay() != null || account.getDueDay() != null
                    || account.getCreditLimitMinor() != null) {
                throw new IllegalArgumentException(
                        "statement day, due day and credit limit apply only to credit cards");
            }
        }
        if (account.getCreditLimitMinor() != null && account.getCreditLimitMinor() < 0) {
            throw new IllegalArgumentException("credit limit cannot be negative");
        }
    }

    /** True when at least one provider is connected and syncing. */
    public boolean hasConnectedProvider() {
        return !connections.findSyncable().isEmpty();
    }
}
