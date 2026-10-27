package com.ledger.api;

import com.ledger.account.Account;
import com.ledger.account.AccountService;
import com.ledger.account.AccountType;
import com.ledger.money.Money;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Accounts and credit cards.
 *
 * <p>Account configuration is what makes automatic attribution work: the
 * {@code instrumentHint} (a card's last four digits, or a UPI handle fragment)
 * is how a parsed alert is matched to the right account. Without it a
 * transaction is still recorded and still counted, but is reported as
 * unattributed rather than guessed at.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** All accounts with their derived balances. */
    @GetMapping
    public List<ApiDtos.AccountDto> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return accounts.balances().stream()
                .filter(balance -> includeArchived || !balance.account().isArchived())
                .map(ApiDtos::toAccountDto)
                .toList();
    }

    @GetMapping("/{id}")
    public ApiDtos.AccountDto get(@PathVariable String id) {
        return ApiDtos.toAccountDto(accounts.balanceOf(accounts.require(id)));
    }

    /**
     * Credit card summaries: outstanding liability, available credit and
     * statement-period spending.
     *
     * <p>Fields are null rather than zero where the card is not configured for
     * them. A limit of zero would read as "no credit available", which is a
     * claim the application has no basis to make.
     */
    @GetMapping("/credit-cards")
    public List<ApiDtos.CreditCardDto> creditCards() {
        return accounts.cardSummaries(LocalDate.now()).stream()
                .map(ApiDtos::toCreditCardDto)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.AccountDto create(@Valid @RequestBody ApiDtos.AccountRequest request) {
        Account account = fromRequest(new Account(request.name(),
                AccountType.parse(request.type()),
                request.currency() == null ? Money.INR : request.currency()), request);
        Account created = accounts.create(account);
        return ApiDtos.toAccountDto(accounts.balanceOf(created));
    }

    @PutMapping("/{id}")
    public ApiDtos.AccountDto update(@PathVariable String id,
                                     @Valid @RequestBody ApiDtos.AccountRequest request) {
        Account changes = fromRequest(new Account(request.name(),
                AccountType.parse(request.type()),
                request.currency() == null ? Money.INR : request.currency()), request);
        Account updated = accounts.update(id, changes);
        return ApiDtos.toAccountDto(accounts.balanceOf(updated));
    }

    /**
     * Archives an account.
     *
     * <p>Not a delete. Deleting would detach every transaction that referenced
     * it, silently converting a year of attributed spending into unattributed
     * spending. Archiving keeps the history intact and removes the account from
     * pickers.
     */
    @DeleteMapping("/{id}")
    public ApiDtos.AccountDto archive(@PathVariable String id) {
        return ApiDtos.toAccountDto(accounts.balanceOf(accounts.archive(id)));
    }

    private static Account fromRequest(Account account, ApiDtos.AccountRequest request) {
        account.setInstitution(request.institution());
        account.setInstrumentHint(request.instrumentHint());
        account.setOpeningBalanceMinor(
                request.openingBalanceMinor() == null ? 0L : request.openingBalanceMinor());
        account.setCreditLimitMinor(request.creditLimitMinor());
        account.setStatementDay(request.statementDay());
        account.setDueDay(request.dueDay());
        return account;
    }
}
