package com.ledger.api;

import com.ledger.category.Category;
import com.ledger.category.CategoryRepository;
import com.ledger.ingest.MatchDecisionRepository;
import com.ledger.money.Money;
import com.ledger.txn.CanonicalTransaction;
import com.ledger.txn.CanonicalTransactionRepository;
import com.ledger.txn.TransactionQuery;
import com.ledger.txn.TransactionService;
import com.ledger.txn.TransactionStatus;
import com.ledger.txn.TransactionType;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transactions: listing with filters, detail with full evidence, and the
 * amendments a user is allowed to make.
 *
 * <p>Manual creation exists but is deliberately not the centre of the API. It
 * is the exception workflow for spending no source can observe — cash, an
 * unsupported bank — and the UI presents it that way.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final int MAX_PAGE_SIZE = 200;

    private final CanonicalTransactionRepository transactions;
    private final TransactionService transactionService;
    private final TransactionMapper mapper;
    private final CategoryRepository categories;
    private final MatchDecisionRepository decisions;

    public TransactionController(CanonicalTransactionRepository transactions,
                                 TransactionService transactionService,
                                 TransactionMapper mapper,
                                 CategoryRepository categories,
                                 MatchDecisionRepository decisions) {
        this.transactions = transactions;
        this.transactionService = transactionService;
        this.mapper = mapper;
        this.categories = categories;
        this.decisions = decisions;
    }

    /**
     * Lists transactions matching the filters.
     *
     * <p>Every filter is optional and they compose, which is what makes the
     * dashboard's drill-downs work: clicking a category slice navigates here
     * with {@code categoryId} and the date range already set.
     */
    @GetMapping
    public ApiDtos.PageDto<ApiDtos.TransactionDto> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) List<String> type,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean uncategorized,
            @RequestParam(required = false) Boolean unattributed,
            @RequestParam(required = false) Boolean needsReview,
            @RequestParam(required = false) Long minAmountMinor,
            @RequestParam(required = false) Long maxAmountMinor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        TransactionQuery query = new TransactionQuery(
                from, to, accountId, categoryId,
                parseTypes(type), parseStatuses(status), search,
                uncategorized, unattributed, needsReview,
                minAmountMinor, maxAmountMinor);

        // A parent category's filter must include its children, so that
        // "Food & Dining" shows Groceries and Restaurants too.
        List<String> childIds = new ArrayList<>();
        if (categoryId != null && !categoryId.isBlank()) {
            for (Category child : categories.findByParentId(categoryId)) {
                childIds.add(child.getId());
            }
        }

        PageRequest pageRequest = PageRequest.of(
                Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "occurredOn")
                        .and(Sort.by(Sort.Direction.DESC, "createdAt")));

        Page<CanonicalTransaction> result =
                transactions.findAll(query.toSpecification(childIds), pageRequest);

        Map<String, String> accountNames = mapper.accountNames();
        List<ApiDtos.TransactionDto> items = result.getContent().stream()
                .map(txn -> mapper.toDto(txn, accountNames))
                .toList();

        return new ApiDtos.PageDto<>(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    /**
     * One transaction with its complete evidence trail: every observation that
     * was linked to it, every matching decision taken, and any refunds against
     * it.
     *
     * <p>This is what makes an automatic decision auditable. If the matcher
     * merged two purchases wrongly, this response shows exactly which messages
     * it merged and on what reasoning.
     */
    @GetMapping("/{id}")
    public ApiDtos.TransactionDetailDto detail(@PathVariable String id) {
        CanonicalTransaction txn = transactionService.require(id);
        Map<String, String> accountNames = mapper.accountNames();

        List<ApiDtos.SourceRecordDto> sources = transactionService.evidenceFor(id).stream()
                .map(ApiDtos::toSourceRecordDto)
                .toList();

        List<ApiDtos.MatchDecisionDto> decisionDtos =
                decisions.findByCanonicalTransactionIdOrderByDecidedAtAsc(id).stream()
                        .map(ApiDtos::toDecisionDto)
                        .toList();

        List<ApiDtos.TransactionDto> refunds = new ArrayList<>();
        for (CanonicalTransaction refund : transactions.findByRefundOfId(id)) {
            refunds.add(mapper.toDto(refund, accountNames));
        }
        for (CanonicalTransaction reversal : transactions.findByReversesId(id)) {
            refunds.add(mapper.toDto(reversal, accountNames));
        }

        return new ApiDtos.TransactionDetailDto(
                mapper.toDto(txn, accountNames), sources, decisionDtos, refunds);
    }

    /**
     * Creates a transaction by hand.
     *
     * <p>Marked {@code MANUAL} so the dashboard can report what share of the
     * ledger needed human transcription.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.TransactionDto create(@Valid @RequestBody ApiDtos.TransactionRequest request) {
        Money amount = Money.ofMinor(request.amountMinor(),
                request.currency() == null ? Money.INR : request.currency());

        CanonicalTransaction created = transactionService.createManual(
                amount, request.occurredOn(), TransactionType.parse(request.type()),
                request.accountId(), request.counterAccountId(), request.merchant(),
                request.categoryId(), request.notes());

        return mapper.toDto(created, mapper.accountNames());
    }

    @PutMapping("/{id}")
    public ApiDtos.TransactionDto update(@PathVariable String id,
                                         @Valid @RequestBody ApiDtos.TransactionRequest request) {
        Money amount = request.amountMinor() == null ? null : Money.ofMinor(request.amountMinor(),
                request.currency() == null ? Money.INR : request.currency());

        CanonicalTransaction updated = transactionService.update(id, amount, request.occurredOn(),
                request.type() == null ? null : TransactionType.parse(request.type()),
                request.accountId(), request.counterAccountId(), request.categoryId(),
                request.notes());

        return mapper.toDto(updated, mapper.accountNames());
    }

    /**
     * Changes a transaction's category, optionally teaching the rule engine.
     *
     * <p>With {@code learn: true} this is the whole of the learning mechanism
     * the requirements ask for: a correction writes a rule at a higher priority
     * than any built-in, so the next transaction from that merchant is filed
     * the user's way without being asked again.
     */
    @PostMapping("/{id}/category")
    public ApiDtos.TransactionDto recategorize(@PathVariable String id,
                                               @Valid @RequestBody ApiDtos.RecategorizeRequest request) {
        CanonicalTransaction updated =
                transactionService.recategorize(id, request.categoryId(), request.learn());
        return mapper.toDto(updated, mapper.accountNames());
    }

    /** Links a refund or reversal to the purchase it offsets. */
    @PostMapping("/{id}/refund-of")
    public ApiDtos.TransactionDto linkRefund(@PathVariable String id,
                                             @Valid @RequestBody ApiDtos.LinkRefundRequest request) {
        CanonicalTransaction updated =
                transactionService.linkRefund(id, request.originalTransactionId());
        return mapper.toDto(updated, mapper.accountNames());
    }

    /** Suggests the purchase a refund probably belongs to. */
    @GetMapping("/{id}/refund-candidates")
    public List<ApiDtos.TransactionDto> refundCandidates(@PathVariable String id) {
        CanonicalTransaction refund = transactionService.require(id);
        Map<String, String> names = mapper.accountNames();
        return transactionService.findRefundOriginal(refund)
                .map(original -> List.of(mapper.toDto(original, names)))
                .orElse(List.of());
    }

    /**
     * Deletes a transaction.
     *
     * <p>The source records that evidenced it are kept and returned to the
     * pipeline, so the underlying messages are not forgotten; they will be
     * re-matched on the next sync.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        transactionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static List<TransactionType> parseTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<TransactionType> types = new ArrayList<>(raw.size());
        for (String value : raw) {
            types.add(TransactionType.parse(value));
        }
        return types;
    }

    private static List<TransactionStatus> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<TransactionStatus> statuses = new ArrayList<>(raw.size());
        for (String value : raw) {
            statuses.add(TransactionStatus.parse(value));
        }
        return statuses;
    }
}
