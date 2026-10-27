package com.ledger.ingest;

import com.ledger.account.Account;
import com.ledger.account.AccountService;
import com.ledger.common.Redaction;
import com.ledger.parse.MessageParser;
import com.ledger.parse.NonTransactionalFilter;
import com.ledger.parse.ParseResult;
import com.ledger.review.ReviewService;
import com.ledger.source.ProviderType;
import com.ledger.source.SourceRecord;
import com.ledger.source.SourceRecordRepository;
import com.ledger.source.SourceRecordStatus;
import com.ledger.sync.SyncRun;
import com.ledger.txn.CanonicalTransaction;
import com.ledger.txn.CanonicalTransactionRepository;
import com.ledger.txn.ReconciliationStatus;
import com.ledger.txn.TransactionOrigin;
import com.ledger.txn.TransactionService;
import com.ledger.txn.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The ingestion pipeline: turns provider messages into ledger entries.
 *
 * <p>One observation flows through four stages, and the sequence is the whole
 * design:
 *
 * <ol>
 *   <li><b>Record.</b> Store the observation as a {@link SourceRecord}, keyed on
 *       the provider's own id. The unique index on
 *       {@code (provider, provider_record_id)} makes this step idempotent, so
 *       re-reading a mailbox cannot produce a second record however many times
 *       sync runs.</li>
 *   <li><b>Parse.</b> Extract what the message states, with a named parser and
 *       a confidence. Nothing is inferred here.</li>
 *   <li><b>Attribute.</b> Resolve the instrument hint to an account, when one
 *       can be resolved unambiguously.</li>
 *   <li><b>Match.</b> Ask {@link MatchingEngine} whether this is a new
 *       financial event, another sighting of a known one, or too close to call.
 *       Write a {@link MatchDecision} either way.</li>
 * </ol>
 *
 * <h2>Idempotency and restart safety</h2>
 * Each record is processed in its own transaction. A crash mid-run leaves
 * already-processed records {@code LINKED} and the rest {@code PENDING_PARSE},
 * and the next run picks up exactly where this one stopped. Because step 1 is
 * idempotent and step 4 is duplicate-aware, re-running a sync over messages
 * already seen is a no-op rather than a source of duplicates.
 *
 * <h2>Nothing is discarded</h2>
 * Every terminal state is recorded and visible. A message that is not a
 * transaction becomes {@code IGNORED} with a reason. A message that should have
 * parsed but did not becomes {@code PARSE_FAILED} and raises a review item with
 * a redacted excerpt. An ambiguous match becomes {@code NEEDS_REVIEW} with the
 * candidates attached. The one thing that never happens is a silent drop.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final SourceRecordRepository sourceRecords;
    private final CanonicalTransactionRepository transactions;
    private final MatchDecisionRepository decisions;
    private final MatchingEngine matcher;
    private final TransactionService transactionService;
    private final AccountService accountService;
    private final ReviewService reviewService;
    private final NonTransactionalFilter nonTransactionalFilter;

    /** Parsers in priority order; the first that can handle a message wins. */
    private final List<MessageParser> parsers;

    private final int maxAttempts;

    public IngestionService(SourceRecordRepository sourceRecords,
                            CanonicalTransactionRepository transactions,
                            MatchDecisionRepository decisions,
                            MatchingEngine matcher,
                            TransactionService transactionService,
                            AccountService accountService,
                            ReviewService reviewService,
                            NonTransactionalFilter nonTransactionalFilter,
                            List<MessageParser> parsers,
                            @Value("${ledger.sync.max-attempts:5}") int maxAttempts) {
        this.sourceRecords = sourceRecords;
        this.transactions = transactions;
        this.decisions = decisions;
        this.matcher = matcher;
        this.transactionService = transactionService;
        this.accountService = accountService;
        this.reviewService = reviewService;
        this.nonTransactionalFilter = nonTransactionalFilter;
        this.parsers = parsers.stream()
                .sorted(Comparator.comparingInt(MessageParser::priority))
                .toList();
        this.maxAttempts = maxAttempts;
    }

    /** What happened to one observation. */
    public enum Result {
        /** A new canonical transaction was created. */
        CREATED,
        /** Linked to a transaction that already existed. */
        LINKED,
        /** An existing pending transaction was settled. */
        SETTLED,
        /** Parked for a human; nothing merged. */
        QUEUED_FOR_REVIEW,
        /** Already ingested; no change. */
        ALREADY_SEEN,
        /** Recognised as not a transaction. */
        IGNORED,
        /** Should have parsed but did not. */
        PARSE_FAILED,
        /** A transient failure; will be retried. */
        RETRY_LATER
    }

    /**
     * Ingests one observation end to end.
     *
     * <p>Runs in its own transaction ({@link Propagation#REQUIRES_NEW}) so that
     * one bad message cannot roll back the progress of an entire sync run.
     *
     * @param run optional counters to update; may be null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result ingest(ProviderType provider, String connectionId,
                         RawObservation observation, SyncRun run) {
        // --- Stage 1: record, idempotently --------------------------------
        Optional<SourceRecord> existing =
                sourceRecords.findByProviderAndProviderRecordId(provider, observation.providerRecordId());
        if (existing.isPresent()) {
            SourceRecord record = existing.get();
            if (record.getStatus().isTerminal()) {
                increment(run, SyncRun::incrementRecordsSkipped);
                return Result.ALREADY_SEEN;
            }
            // Stored previously but never finished: fall through and retry it.
            return process(record, observation, run);
        }

        SourceRecord record = new SourceRecord(provider, observation.providerRecordId());
        record.setProviderConnectionId(connectionId);
        record.setSender(observation.sender());
        record.setSubject(Redaction.excerpt(observation.subject()));
        record.setReceivedAt(observation.receivedAt());
        record.setContentHash(Redaction.contentHash(observation.content()));
        record.setRedactedExcerpt(Redaction.excerpt(observation.content()));
        record = sourceRecords.save(record);
        increment(run, SyncRun::incrementRecordsCreated);

        return process(record, observation, run);
    }

    /** Stages 2 to 4 for a stored record. */
    private Result process(SourceRecord record, RawObservation observation, SyncRun run) {
        record.recordAttempt();

        if (record.getAttemptCount() > maxAttempts) {
            // Out of retries. The record stays visible in the exception queue
            // as a coverage gap rather than being retried forever.
            record.setStatus(SourceRecordStatus.PARSE_FAILED);
            record.setParseError("gave up after " + maxAttempts + " attempts");
            sourceRecords.save(record);
            reviewService.raiseParseFailure(record.getId(),
                    "Gave up after " + maxAttempts + " attempts: " + summarise(record));
            increment(run, SyncRun::incrementParseFailures);
            return Result.PARSE_FAILED;
        }

        try {
            // --- Not a transaction at all ---------------------------------
            Optional<String> ignoreReason = nonTransactionalFilter.reasonToIgnore(observation);
            if (ignoreReason.isPresent()) {
                record.setStatus(SourceRecordStatus.IGNORED);
                record.setParseError(ignoreReason.get());
                sourceRecords.save(record);
                return Result.IGNORED;
            }

            // --- Stage 2: parse -------------------------------------------
            Optional<ParsedBy> parsed = runParsers(observation);
            if (parsed.isEmpty()) {
                // No parser claimed it. Not an error: most financial mail is
                // not a transaction. Recorded so it is not re-examined.
                record.setStatus(SourceRecordStatus.IGNORED);
                record.setParseError("no parser recognised this message as a transaction");
                sourceRecords.save(record);
                return Result.IGNORED;
            }

            ParseResult result = parsed.get().result();
            record.setParserId(parsed.get().parserId());
            record.setParseConfidence(result.confidence());
            record.setExtractedAmount(result.amount());
            record.setExtractedOccurredOn(result.occurredOn());
            record.setExtractedMerchant(result.merchantRaw());
            record.setExtractedType(result.type());
            record.setExtractedStatus(result.status());
            record.setExtractedInstrumentHint(result.instrumentHint());
            record.setExtractedReference(result.reference());

            if (!record.hasMinimumFields()) {
                // Transaction-shaped but incomplete: this IS a failure, because
                // a real transaction was probably described and could not be
                // captured. Surfaced rather than dropped.
                record.setStatus(SourceRecordStatus.PARSE_FAILED);
                record.setParseError(missingFieldsMessage(record));
                sourceRecords.save(record);
                reviewService.raiseParseFailure(record.getId(),
                        "Incomplete transaction: " + missingFieldsMessage(record)
                                + ". Excerpt: " + nullSafe(record.getRedactedExcerpt()));
                increment(run, SyncRun::incrementParseFailures);
                return Result.PARSE_FAILED;
            }

            // --- Stage 3: attribute to an account -------------------------
            String accountId = resolveAccount(record).map(Account::getId).orElse(null);

            // A transfer needs both sides and the schema enforces it. Without a
            // resolvable counter account the row cannot be written, so the
            // record is queued rather than forced into a wrong shape.
            if (record.getExtractedType() == TransactionType.TRANSFER) {
                record.setStatus(SourceRecordStatus.NEEDS_REVIEW);
                record.setParseError("self-transfer needs the destination account confirmed");
                sourceRecords.save(record);
                reviewService.raiseUnknownAccount(record.getId(), null,
                        "Transfer detected but the destination account is unknown: " + summarise(record));
                increment(run, SyncRun::incrementQueuedForReview);
                return Result.QUEUED_FOR_REVIEW;
            }

            // --- Stage 4: match against the existing ledger ---------------
            CanonicalTransaction contentDuplicate = findContentDuplicate(record);
            MatchingEngine.Outcome outcome = matcher.match(record, accountId, contentDuplicate);

            return switch (outcome.action()) {
                case DUPLICATE_CONTENT -> handleContentDuplicate(record, outcome, run);
                case LINK_EXISTING -> handleLink(record, outcome, run);
                case SETTLE_PENDING -> handleSettle(record, outcome, run);
                case REVIEW -> handleReview(record, outcome, run);
                case CREATE_NEW -> handleCreate(record, outcome, accountId, observation, run);
            };

        } catch (RuntimeException e) {
            // Transient or unexpected: back off and let a later run retry. The
            // message is intentionally not logged, only the exception type, so
            // a stack trace cannot leak message content into the log.
            record.setStatus(SourceRecordStatus.PENDING_PARSE);
            record.setParseError(e.getClass().getSimpleName());
            record.setNextAttemptAt(backoffFrom(record.getAttemptCount()));
            sourceRecords.save(record);
            log.warn("Deferring source record {} after {} ({} of {} attempts)",
                    record.getId(), e.getClass().getSimpleName(), record.getAttemptCount(), maxAttempts);
            return Result.RETRY_LATER;
        }
    }

    // --- Outcome handlers ---------------------------------------------------

    private Result handleCreate(SourceRecord record, MatchingEngine.Outcome outcome,
                                String accountId, RawObservation observation, SyncRun run) {
        boolean cashWithdrawal = com.ledger.parse.TransactionTextExtractor
                .isCashWithdrawal(observation.searchableText());

        CanonicalTransaction txn = transactionService.createFromSource(
                record, accountId, cashWithdrawal, TransactionOrigin.AUTOMATIC);

        // A refund is more useful attached to its purchase: the offset then
        // lands in the same category the spending was counted in.
        if (txn.getType() == TransactionType.REFUND || txn.getType() == TransactionType.REVERSAL) {
            transactionService.findRefundOriginal(txn).ifPresent(original ->
                    transactionService.linkRefund(txn.getId(), original.getId()));
        }

        link(record, txn.getId(), SourceRecordStatus.LINKED);
        decisions.save(new MatchDecision(record.getId(), txn.getId(),
                MatchDecision.Decision.CREATED_NEW, outcome.strategy(), outcome.score(),
                outcome.rationale()));

        if (accountId == null && !record.getProvider().isFileBased()) {
            // The spend is counted, but it is not attributable to an account,
            // so account balances are knowingly incomplete. Say so.
            reviewService.raiseUnknownAccount(record.getId(), txn.getId(),
                    "Could not match the instrument to a configured account: " + summarise(record));
        }

        increment(run, SyncRun::incrementTransactionsCreated);
        return Result.CREATED;
    }

    private Result handleLink(SourceRecord record, MatchingEngine.Outcome outcome, SyncRun run) {
        CanonicalTransaction target = outcome.target();

        // A second independent sighting is the strongest automatic evidence
        // that the amount is real and counted exactly once.
        target.setReconciliationStatus(ReconciliationStatus.MATCHED);
        enrich(target, record);
        transactions.save(target);

        link(record, target.getId(), SourceRecordStatus.LINKED);
        decisions.save(new MatchDecision(record.getId(), target.getId(),
                MatchDecision.Decision.LINKED_EXISTING, outcome.strategy(), outcome.score(),
                outcome.rationale()));

        increment(run, SyncRun::incrementTransactionsLinked);
        return Result.LINKED;
    }

    private Result handleSettle(SourceRecord record, MatchingEngine.Outcome outcome, SyncRun run) {
        CanonicalTransaction target = outcome.target();
        transactionService.settlePending(target, record.extractedAmount(),
                record.getExtractedOccurredOn(), record.getExtractedReference());

        link(record, target.getId(), SourceRecordStatus.LINKED);
        decisions.save(new MatchDecision(record.getId(), target.getId(),
                MatchDecision.Decision.UPDATED_PENDING_TO_POSTED, outcome.strategy(),
                outcome.score(), outcome.rationale()));

        increment(run, SyncRun::incrementTransactionsLinked);
        return Result.SETTLED;
    }

    private Result handleReview(SourceRecord record, MatchingEngine.Outcome outcome, SyncRun run) {
        record.setStatus(SourceRecordStatus.NEEDS_REVIEW);
        sourceRecords.save(record);

        List<ReviewService.Candidate> candidates = new ArrayList<>();
        for (MatchingEngine.ScoredCandidate candidate : outcome.candidates()) {
            CanonicalTransaction txn = candidate.transaction();
            candidates.add(new ReviewService.Candidate(txn.getId(), candidate.score(),
                    candidate.rationale(), txn.getAmountMinor(), txn.getCurrency(),
                    txn.getOccurredOn(), txn.getMerchant()));
        }

        reviewService.raiseAmbiguousMatch(record.getId(),
                "Possible duplicate of an existing transaction: " + summarise(record)
                        + ". " + outcome.rationale(),
                candidates);

        decisions.save(new MatchDecision(record.getId(), null,
                MatchDecision.Decision.QUEUED_FOR_REVIEW, outcome.strategy(), outcome.score(),
                outcome.rationale()));

        increment(run, SyncRun::incrementQueuedForReview);
        return Result.QUEUED_FOR_REVIEW;
    }

    private Result handleContentDuplicate(SourceRecord record, MatchingEngine.Outcome outcome, SyncRun run) {
        link(record, outcome.target().getId(), SourceRecordStatus.LINKED);
        decisions.save(new MatchDecision(record.getId(), outcome.target().getId(),
                MatchDecision.Decision.REJECTED_DUPLICATE_CONTENT, outcome.strategy(),
                outcome.score(), outcome.rationale()));
        increment(run, SyncRun::incrementRecordsSkipped);
        return Result.ALREADY_SEEN;
    }

    // --- Helpers ------------------------------------------------------------

    private record ParsedBy(String parserId, ParseResult result) {
    }

    /**
     * Offers the message to each parser in priority order and takes the first
     * non-empty result.
     *
     * <p>A parser that throws is treated as declining rather than failing the
     * record: one badly-behaved parser must not block every message. The
     * failure is logged by parser id so it is diagnosable.
     */
    private Optional<ParsedBy> runParsers(RawObservation observation) {
        for (MessageParser parser : parsers) {
            try {
                if (!parser.canParse(observation)) {
                    continue;
                }
                Optional<ParseResult> result = parser.parse(observation);
                if (result.isPresent()) {
                    return Optional.of(new ParsedBy(parser.id(), result.get()));
                }
            } catch (RuntimeException e) {
                log.warn("Parser {} failed with {}; trying the next parser",
                        parser.id(), e.getClass().getSimpleName());
            }
        }
        return Optional.empty();
    }

    private Optional<Account> resolveAccount(SourceRecord record) {
        return accountService.resolveByInstrumentHint(record.getExtractedInstrumentHint());
    }

    /**
     * An existing transaction whose evidence includes a record with identical
     * content. Catches the same alert delivered twice under different message
     * ids, which the provider-id check cannot.
     */
    private CanonicalTransaction findContentDuplicate(SourceRecord record) {
        if (record.getContentHash() == null) {
            return null;
        }
        for (SourceRecord other : sourceRecords.findByContentHash(record.getContentHash())) {
            if (!other.getId().equals(record.getId()) && other.getCanonicalTransactionId() != null) {
                return transactions.findById(other.getCanonicalTransactionId()).orElse(null);
            }
        }
        return null;
    }

    /**
     * Fills gaps on an existing transaction from a newly linked observation.
     *
     * <p>Only ever fills in what is missing; never overwrites. A statement line
     * can supply the account a merchant receipt could not, and an alert can
     * supply the reference. Overwriting instead would let a vaguer later source
     * degrade good data.
     */
    private void enrich(CanonicalTransaction target, SourceRecord record) {
        if (target.getExternalReference() == null && record.getExtractedReference() != null) {
            target.setExternalReference(record.getExtractedReference());
        }
        if (target.getAccountId() == null) {
            resolveAccount(record).ifPresent(account -> target.setAccountId(account.getId()));
        }
        if (target.getMerchantRaw() == null && record.getExtractedMerchant() != null) {
            target.setMerchantRaw(record.getExtractedMerchant());
        }
    }

    private void link(SourceRecord record, String transactionId, SourceRecordStatus status) {
        record.setCanonicalTransactionId(transactionId);
        record.setStatus(status);
        record.setParseError(null);
        record.setNextAttemptAt(null);
        sourceRecords.save(record);
    }

    /**
     * Exponential backoff with a cap: 1, 2, 4, 8, 16 minutes. Stops a
     * persistently failing record from being retried on every single poll.
     */
    private static Instant backoffFrom(int attemptCount) {
        long minutes = (long) Math.pow(2, Math.min(attemptCount, 4));
        return Instant.now().plus(Duration.ofMinutes(minutes));
    }

    private static String missingFieldsMessage(SourceRecord record) {
        List<String> missing = new ArrayList<>();
        if (record.getExtractedAmountMinor() == null) {
            missing.add("amount");
        }
        if (record.getExtractedOccurredOn() == null) {
            missing.add("date");
        }
        if (record.getExtractedType() == null) {
            missing.add("direction");
        }
        return "missing " + String.join(", ", missing);
    }

    /** A short, non-sensitive summary for review text. */
    private static String summarise(SourceRecord record) {
        StringBuilder sb = new StringBuilder();
        if (record.getExtractedAmountMinor() != null) {
            sb.append(record.extractedAmount());
        } else {
            sb.append("unknown amount");
        }
        if (record.getExtractedMerchant() != null) {
            sb.append(" at ").append(record.getExtractedMerchant());
        }
        if (record.getExtractedOccurredOn() != null) {
            sb.append(" on ").append(record.getExtractedOccurredOn());
        }
        return sb.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? "(none)" : value;
    }

    private static void increment(SyncRun run, java.util.function.Consumer<SyncRun> action) {
        if (run != null) {
            action.accept(run);
        }
    }

    /**
     * Retries records left unfinished by an earlier run whose backoff has
     * elapsed. Called by the scheduler after each provider poll so that a
     * transient failure does not need a new message to arrive to be retried.
     */
    public int retryPending(int limit) {
        List<SourceRecord> ready = sourceRecords.findReadyForProcessing(
                Instant.now(), maxAttempts, org.springframework.data.domain.PageRequest.of(0, limit));
        int processed = 0;
        for (SourceRecord record : ready) {
            // The original message body is long gone, which is deliberate. What
            // can be retried from stored state is the match, not the parse, so
            // only already-parsed records are resumable here.
            if (record.hasMinimumFields()) {
                try {
                    retryMatch(record);
                    processed++;
                } catch (RuntimeException e) {
                    log.warn("Retry of source record {} failed with {}",
                            record.getId(), e.getClass().getSimpleName());
                }
            } else {
                // Cannot re-parse without the body; surface it as a gap.
                record.setStatus(SourceRecordStatus.PARSE_FAILED);
                record.setParseError("cannot be re-parsed: message content is not retained");
                sourceRecords.save(record);
                reviewService.raiseParseFailure(record.getId(),
                        "Could not be parsed and the message body is not retained: "
                                + nullSafe(record.getRedactedExcerpt()));
            }
        }
        return processed;
    }

    /** Re-runs stages 3 and 4 for a record that was already parsed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result retryMatch(SourceRecord record) {
        record.recordAttempt();
        String accountId = resolveAccount(record).map(Account::getId).orElse(null);
        CanonicalTransaction contentDuplicate = findContentDuplicate(record);
        MatchingEngine.Outcome outcome = matcher.match(record, accountId, contentDuplicate);

        return switch (outcome.action()) {
            case DUPLICATE_CONTENT -> handleContentDuplicate(record, outcome, null);
            case LINK_EXISTING -> handleLink(record, outcome, null);
            case SETTLE_PENDING -> handleSettle(record, outcome, null);
            case REVIEW -> handleReview(record, outcome, null);
            case CREATE_NEW -> {
                CanonicalTransaction txn = transactionService.createFromSource(
                        record, accountId, false, TransactionOrigin.AUTOMATIC);
                link(record, txn.getId(), SourceRecordStatus.LINKED);
                decisions.save(new MatchDecision(record.getId(), txn.getId(),
                        MatchDecision.Decision.CREATED_NEW, outcome.strategy(), outcome.score(),
                        outcome.rationale()));
                yield Result.CREATED;
            }
        };
    }

    /** Parser inventory for the Data Sources screen. */
    public List<MessageParser> parsers() {
        return parsers;
    }
}
