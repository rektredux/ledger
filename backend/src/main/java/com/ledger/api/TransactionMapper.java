package com.ledger.api;

import com.ledger.account.Account;
import com.ledger.account.AccountService;
import com.ledger.category.CategoryService;
import com.ledger.source.SourceRecordRepository;
import com.ledger.txn.CanonicalTransaction;
import com.ledger.txn.LedgerMath;
import com.ledger.txn.TransactionDescriptions;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds transaction DTOs, resolving the display names and the derived flags.
 *
 * <p>Account and category names are resolved here rather than client-side so a
 * transaction list is renderable without the frontend holding and joining
 * lookup tables. {@code isSpending} is computed here too: the accounting rules
 * that decide it live in {@link LedgerMath} and must have exactly one
 * implementation, not a second one in TypeScript that can drift.
 *
 * <p>Account lookups go through a per-call map built once, so listing a page of
 * transactions is two queries rather than one per row.
 */
@Component
public class TransactionMapper {

    private final CategoryService categories;
    private final AccountService accounts;
    private final SourceRecordRepository sourceRecords;

    public TransactionMapper(CategoryService categories,
                             AccountService accounts,
                             SourceRecordRepository sourceRecords) {
        this.categories = categories;
        this.accounts = accounts;
        this.sourceRecords = sourceRecords;
    }

    /** A reusable name lookup, built once per request. */
    public Map<String, String> accountNames() {
        Map<String, String> names = new HashMap<>();
        for (Account account : accounts.findAll()) {
            names.put(account.getId(), account.getName());
        }
        return names;
    }

    public ApiDtos.TransactionDto toDto(CanonicalTransaction txn, Map<String, String> accountNames) {
        boolean categoryExcluded = categories.isExcludedFromSpending(txn.getCategoryId());

        return new ApiDtos.TransactionDto(
                txn.getId(),
                txn.getAmountMinor(),
                txn.getCurrency(),
                txn.getOccurredOn(),
                txn.getOccurredAtTime(),
                txn.getPostedOn(),
                txn.getType().name(),
                TransactionDescriptions.label(txn.getType()),
                txn.getStatus().name(),
                txn.getMerchant(),
                txn.getMerchantRaw(),
                txn.getCategoryId(),
                categories.displayName(txn.getCategoryId()),
                categories.displayPath(txn.getCategoryId()),
                txn.getCategorySource().name(),
                txn.getCategoryConfidence(),
                txn.getAccountId(),
                txn.getAccountId() == null ? null : accountNames.get(txn.getAccountId()),
                txn.getCounterAccountId(),
                txn.getCounterAccountId() == null ? null : accountNames.get(txn.getCounterAccountId()),
                txn.getDescription(),
                txn.getNotes(),
                txn.getExternalReference(),
                txn.getRefundOfId(),
                txn.getReversesId(),
                txn.getReconciliationStatus().name(),
                txn.getOrigin().name(),
                LedgerMath.countsAsSpending(txn, categoryExcluded),
                LedgerMath.countsAsIncome(txn),
                sourceRecords.findByCanonicalTransactionId(txn.getId()).size(),
                txn.getCreatedAt(),
                txn.getUpdatedAt());
    }

    public List<ApiDtos.TransactionDto> toDtos(List<CanonicalTransaction> txns) {
        Map<String, String> names = accountNames();
        return txns.stream().map(txn -> toDto(txn, names)).toList();
    }
}
