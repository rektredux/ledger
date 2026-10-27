package com.ledger.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the queue of things the pipeline could not decide on its own.
 *
 * <p>Raising a review item is how the application satisfies "never silently
 * discard an ambiguous transaction". Every call site that could otherwise guess
 * writes an item here instead and carries on, so a questionable record is
 * visible rather than either dropped or merged on a coin flip.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewItemRepository repository;
    private final ObjectMapper objectMapper;

    public ReviewService(ReviewItemRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * One candidate transaction an ambiguous source record might belong to,
     * with the reasoning. Serialised into {@code candidates_json} so the UI can
     * present the choice without recomputing the match.
     */
    public record Candidate(String transactionId,
                            double score,
                            String rationale,
                            long amountMinor,
                            String currency,
                            LocalDate occurredOn,
                            String merchant) {
    }

    /**
     * Raises an ambiguous-match item, unless one is already open for this
     * source record.
     *
     * <p>The idempotency check matters because sync is retried: without it, a
     * record that stays ambiguous would accumulate one item per sync run and
     * bury the queue.
     */
    @Transactional
    public Optional<ReviewItem> raiseAmbiguousMatch(String sourceRecordId,
                                                    String summary,
                                                    List<Candidate> candidates) {
        if (repository.hasOpenItem(sourceRecordId, ReviewItem.Kind.AMBIGUOUS_MATCH)) {
            return Optional.empty();
        }
        ReviewItem item = new ReviewItem(ReviewItem.Kind.AMBIGUOUS_MATCH, summary);
        item.setSourceRecordId(sourceRecordId);
        item.setCandidatesJson(writeCandidates(candidates));
        log.info("Queued ambiguous match for review (sourceRecord={}, candidates={})",
                sourceRecordId, candidates == null ? 0 : candidates.size());
        return Optional.of(repository.save(item));
    }

    /**
     * Raises a parse-failure item. The summary carries the already-redacted
     * excerpt so the user can see what coverage is missing.
     */
    @Transactional
    public Optional<ReviewItem> raiseParseFailure(String sourceRecordId, String summary) {
        if (repository.hasOpenItem(sourceRecordId, ReviewItem.Kind.PARSE_FAILURE)) {
            return Optional.empty();
        }
        ReviewItem item = new ReviewItem(ReviewItem.Kind.PARSE_FAILURE, summary);
        item.setSourceRecordId(sourceRecordId);
        return Optional.of(repository.save(item));
    }

    /** Raises an uncategorized-transaction item. */
    @Transactional
    public Optional<ReviewItem> raiseUnknownCategory(String transactionId, String summary) {
        if (repository.findByCanonicalTransactionIdAndStatus(transactionId, ReviewItem.Status.OPEN).isPresent()) {
            return Optional.empty();
        }
        ReviewItem item = new ReviewItem(ReviewItem.Kind.UNKNOWN_CATEGORY, summary);
        item.setCanonicalTransactionId(transactionId);
        return Optional.of(repository.save(item));
    }

    /**
     * Raises an item for a transaction whose instrument could not be matched to
     * a configured account.
     */
    @Transactional
    public Optional<ReviewItem> raiseUnknownAccount(String sourceRecordId, String transactionId, String summary) {
        if (sourceRecordId != null
                && repository.hasOpenItem(sourceRecordId, ReviewItem.Kind.UNKNOWN_ACCOUNT)) {
            return Optional.empty();
        }
        ReviewItem item = new ReviewItem(ReviewItem.Kind.UNKNOWN_ACCOUNT, summary);
        item.setSourceRecordId(sourceRecordId);
        item.setCanonicalTransactionId(transactionId);
        return Optional.of(repository.save(item));
    }

    /** Raises a reconciliation-gap item from a statement import. */
    @Transactional
    public ReviewItem raiseReconciliationGap(String summary, List<Candidate> candidates) {
        ReviewItem item = new ReviewItem(ReviewItem.Kind.RECONCILIATION_GAP, summary);
        item.setCandidatesJson(writeCandidates(candidates));
        return repository.save(item);
    }

    // --- Reads --------------------------------------------------------------

    public Page<ReviewItem> open(Pageable pageable) {
        return repository.findByStatusOrderByCreatedAtDesc(ReviewItem.Status.OPEN, pageable);
    }

    public Page<ReviewItem> open(ReviewItem.Kind kind, Pageable pageable) {
        return repository.findByStatusAndKindOrderByCreatedAtDesc(ReviewItem.Status.OPEN, kind, pageable);
    }

    public long openCount() {
        return repository.countByStatus(ReviewItem.Status.OPEN);
    }

    /** Open counts per kind, with zeros for kinds that have none. */
    public Map<ReviewItem.Kind, Long> openCountsByKind() {
        Map<ReviewItem.Kind, Long> counts = new EnumMap<>(ReviewItem.Kind.class);
        for (ReviewItem.Kind kind : ReviewItem.Kind.values()) {
            counts.put(kind, 0L);
        }
        for (ReviewItemRepository.KindCount row : repository.countOpenByKind()) {
            counts.put(row.getKind(), row.getItemCount());
        }
        return counts;
    }

    public Optional<ReviewItem> find(String id) {
        return repository.findById(id);
    }

    public List<Candidate> readCandidates(ReviewItem item) {
        if (item.getCandidatesJson() == null || item.getCandidatesJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(Candidate.class).readValue(item.getCandidatesJson());
        } catch (JsonProcessingException e) {
            log.warn("Could not read review candidates for item {}", item.getId());
            return List.of();
        }
    }

    // --- Writes -------------------------------------------------------------

    @Transactional
    public ReviewItem resolve(String id, String resolution) {
        ReviewItem item = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown review item: " + id));
        item.resolve(resolution);
        return repository.save(item);
    }

    @Transactional
    public ReviewItem dismiss(String id, String reason) {
        ReviewItem item = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown review item: " + id));
        item.dismiss(reason);
        return repository.save(item);
    }

    /** Closes any open items attached to a source record, after it is resolved. */
    @Transactional
    public void resolveForSourceRecord(String sourceRecordId, String resolution) {
        for (ReviewItem item : repository.findBySourceRecordId(sourceRecordId)) {
            if (item.isOpen()) {
                item.resolve(resolution);
                repository.save(item);
            }
        }
    }

    /** Closes an open category question once the transaction has a category. */
    @Transactional
    public void resolveCategoryQuestion(String transactionId, String resolution) {
        repository.findByCanonicalTransactionIdAndStatus(transactionId, ReviewItem.Status.OPEN)
                .filter(item -> item.getKind() == ReviewItem.Kind.UNKNOWN_CATEGORY)
                .ifPresent(item -> {
                    item.resolve(resolution);
                    repository.save(item);
                });
    }

    private String writeCandidates(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(candidates);
        } catch (JsonProcessingException e) {
            // The item itself is more important than its candidate detail;
            // losing the detail must not prevent the question being asked.
            log.warn("Could not serialise review candidates: {}", e.getMessage());
            return null;
        }
    }
}
