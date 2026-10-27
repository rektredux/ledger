package com.ledger.api;

import com.ledger.provider.gmail.GmailProvider;
import com.ledger.report.DashboardService;
import com.ledger.review.ReviewItem;
import com.ledger.review.ReviewService;
import com.ledger.source.ProviderConnection;
import com.ledger.source.ProviderConnectionRepository;
import com.ledger.source.ProviderType;
import com.ledger.source.SourceRecordRepository;
import com.ledger.sync.SyncRun;
import com.ledger.sync.SyncRunRepository;
import com.ledger.sync.SyncService;
import com.ledger.txn.TransactionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data source management: connection, OAuth callbacks, manual sync, and the
 * review queue.
 *
 * <h2>OAuth state validation</h2>
 * The random {@code state} parameter ties a callback to the request that
 * initiated it. It is stored in memory here (rather than a session or the
 * database) because the application is single-user and local, so a restart
 * between "click connect" and "the callback arrives" is the only scenario where
 * state lookup can fail — which is harmless (the user reconnects) and far less
 * likely than the alternative failure modes a session cookie or a database
 * record would introduce.
 */
@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private static final Logger log = LoggerFactory.getLogger(SourceController.class);

    /** One entry per in-flight OAuth request, keyed by state parameter. */
    private final ConcurrentHashMap<String, Instant> pendingStates = new ConcurrentHashMap<>();

    private final SyncService syncService;
    private final SyncRunRepository syncRuns;
    private final ProviderConnectionRepository connections;
    private final SourceRecordRepository sourceRecords;
    private final GmailProvider gmailProvider;
    private final DashboardService dashboard;
    private final ReviewService reviewService;
    private final TransactionService transactionService;
    private final TransactionMapper transactionMapper;
    private final String frontendBaseUrl;

    public SourceController(SyncService syncService,
                            SyncRunRepository syncRuns,
                            ProviderConnectionRepository connections,
                            SourceRecordRepository sourceRecords,
                            GmailProvider gmailProvider,
                            DashboardService dashboard,
                            ReviewService reviewService,
                            TransactionService transactionService,
                            TransactionMapper transactionMapper,
                            @Value("${ledger.frontend.base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.syncService = syncService;
        this.syncRuns = syncRuns;
        this.connections = connections;
        this.sourceRecords = sourceRecords;
        this.gmailProvider = gmailProvider;
        this.dashboard = dashboard;
        this.reviewService = reviewService;
        this.transactionService = transactionService;
        this.transactionMapper = transactionMapper;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    // --- Provider health and history ---------------------------------------

    @GetMapping
    public List<DashboardService.SourceHealth> health() {
        return dashboard.sourceHealth();
    }

    @GetMapping("/runs")
    public List<SyncRun> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        return syncRuns.findAll(
                PageRequest.of(0, Math.clamp(limit, 1, 100))).getContent();
    }

    @GetMapping("/{provider}/runs")
    public List<SyncRun> runsForProvider(@PathVariable String provider,
                                          @RequestParam(defaultValue = "20") int limit) {
        return connections.findByProvider(ProviderType.valueOf(provider.toUpperCase()))
                .map(conn -> syncRuns.findByProviderConnectionIdOrderByStartedAtDesc(
                        conn.getId(), PageRequest.of(0, Math.clamp(limit, 1, 100))))
                .orElse(List.of());
    }

    // --- Manual sync -------------------------------------------------------

    /**
     * Triggers a manual sync for one provider.
     *
     * <p>Returns immediately with a status object rather than waiting for the
     * sync to complete, because a backfill can take minutes and HTTP timeouts
     * are shorter than that. The UI polls {@code /api/sources/{provider}/runs}
     * to see progress.
     */
    @PostMapping("/{provider}/sync")
    public Map<String, Object> manualSync(@PathVariable String provider) {
        ProviderType type = ProviderType.valueOf(provider.toUpperCase());
        Optional<SyncRun> run = syncService.sync(type, SyncRun.Mode.MANUAL);

        Map<String, Object> response = new HashMap<>();
        if (run.isEmpty()) {
            response.put("triggered", false);
            response.put("reason", "provider not connected or sync already running");
        } else {
            response.put("triggered", true);
            response.put("runId", run.get().getId());
            response.put("status", run.get().getStatus());
        }
        return response;
    }

    // --- Gmail OAuth -------------------------------------------------------

    /**
     * Checks whether the Gmail connector is configured (i.e. the deployment
     * has OAuth client credentials).
     *
     * <p>Separate from connection status: "not configured" means the operator
     * needs to create a Google Cloud OAuth client and set the environment
     * variables. "Disconnected" means it is configured but the user has not
     * clicked connect yet.
     */
    @GetMapping("/gmail/status")
    public Map<String, Object> gmailStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("configured", gmailProvider.isConfigured());
        connections.findByProvider(ProviderType.GMAIL).ifPresentOrElse(conn -> {
            status.put("connected", conn.getStatus() == ProviderConnection.Status.CONNECTED);
            status.put("status", conn.getStatus().name());
            status.put("displayName", conn.getDisplayName());
            status.put("lastSuccessAt", conn.getLastSuccessAt());
            status.put("backfillComplete", conn.isBackfillComplete());
            status.put("backfillThrough", conn.getBackfillThrough());
            status.put("consecutiveFailures", conn.getConsecutiveFailures());
            if (conn.getLastError() != null) {
                status.put("lastError", conn.getLastError());
            }
        }, () -> {
            status.put("connected", false);
            status.put("status", "NOT_CONNECTED");
        });
        return status;
    }

    /**
     * Starts the Gmail OAuth authorization flow.
     *
     * <p>Returns the authorization URL. The UI opens it in a new tab; the user
     * completes the consent screen on Google's side, and Google redirects to
     * {@code /api/sources/gmail/callback}.
     */
    @GetMapping("/gmail/authorize")
    public ResponseEntity<Map<String, String>> authorize() {
        if (!gmailProvider.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Gmail is not configured. Set GMAIL_CLIENT_ID and GMAIL_CLIENT_SECRET."));
        }
        String state = gmailProvider.type().name()
                + "." + Long.toHexString(System.nanoTime());
        pendingStates.put(state, Instant.now());
        Optional<String> url = gmailProvider.authorizationUrl(state);
        if (url.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Could not generate authorization URL. Check credentials."));
        }
        return ResponseEntity.ok(Map.of("url", url.get(), "state", state));
    }

    /**
     * Google's redirect after the user approves (or declines) the consent
     * screen.
     *
     * <p>On success this completes the authorization and redirects the browser
     * to the Data Sources page in the frontend. On failure it redirects to an
     * error page with a query parameter.
     *
     * <p>This endpoint must match the Authorized Redirect URI registered in the
     * Google Cloud Console exactly. See the README for setup instructions.
     */
    @GetMapping("/gmail/callback")
    public void gmailCallback(@RequestParam(required = false) String code,
                              @RequestParam(required = false) String state,
                              @RequestParam(required = false) String error,
                              HttpServletResponse response) throws IOException {

        if (error != null || code == null) {
            String reason = error == null ? "no_code" : error;
            log.info("Gmail authorization declined or failed: {}", reason);
            response.sendRedirect(frontendBaseUrl + "/sources?error=" + reason);
            return;
        }

        // State validation prevents an attacker-initiated callback from
        // attaching their mailbox to this installation.
        if (state == null || !pendingStates.containsKey(state)) {
            log.warn("Gmail callback received with unrecognised or missing state parameter");
            response.sendRedirect(frontendBaseUrl + "/sources?error=invalid_state");
            return;
        }
        pendingStates.remove(state);

        try {
            ProviderConnection connection = connections.findByProvider(ProviderType.GMAIL)
                    .orElseGet(() -> {
                        ProviderConnection conn = new ProviderConnection(ProviderType.GMAIL);
                        return connections.save(conn);
                    });

            gmailProvider.completeAuthorization(connection, code);
            connections.save(connection);

            log.info("Gmail connected for {}", com.ledger.common.Redaction.maskEmail(
                    connection.getDisplayName()));
            response.sendRedirect(frontendBaseUrl + "/sources?connected=gmail");

        } catch (Exception e) {
            log.warn("Gmail authorization failed: {}", e.getClass().getSimpleName());
            response.sendRedirect(frontendBaseUrl + "/sources?error=authorization_failed");
        }
    }

    /**
     * Disconnects Gmail.
     *
     * <p>Revokes the Google grant, clears local tokens, and tells the user to
     * also visit {@code myaccount.google.com/permissions} — the application
     * cannot guarantee a network call succeeded.
     */
    @DeleteMapping("/gmail")
    public Map<String, Object> disconnectGmail() {
        Optional<ProviderConnection> maybeConn = connections.findByProvider(ProviderType.GMAIL);
        if (maybeConn.isEmpty()) {
            return Map.of("disconnected", false, "reason", "not connected");
        }
        ProviderConnection conn = maybeConn.get();
        boolean revoked = false;
        try {
            gmailProvider.disconnect(conn);
            revoked = true;
        } catch (Exception e) {
            log.warn("Token revocation failed: {}", e.getClass().getSimpleName());
            conn.clearTokens();
            conn.setStatus(ProviderConnection.Status.DISCONNECTED);
        }
        connections.save(conn);
        return Map.of(
                "disconnected", true,
                "tokenRevoked", revoked,
                "note", "Visit https://myaccount.google.com/permissions to verify the grant was removed.");
    }

    // --- Review queue ------------------------------------------------------

    @GetMapping("/review")
    public ApiDtos.PageDto<ApiDtos.ReviewItemDto> reviewQueue(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageRequest = PageRequest.of(
                Math.max(0, page), Math.clamp(size, 1, 100));

        Page<ReviewItem> items = kind == null
                ? reviewService.open(pageRequest)
                : reviewService.open(ReviewItem.Kind.valueOf(kind.toUpperCase()), pageRequest);

        return new ApiDtos.PageDto<>(
                items.getContent().stream().map(this::toReviewDto).toList(),
                items.getNumber(), items.getSize(),
                items.getTotalElements(), items.getTotalPages());
    }

    @GetMapping("/review/counts")
    public Map<String, Long> reviewCounts() {
        Map<String, Long> counts = new HashMap<>();
        reviewService.openCountsByKind().forEach((kind, count) ->
                counts.put(kind.name(), count));
        return counts;
    }

    @GetMapping("/review/{id}")
    public ApiDtos.ReviewItemDto reviewItem(@PathVariable String id) {
        ReviewItem item = reviewService.find(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown review item: " + id));
        return toReviewDto(item);
    }

    /**
     * Resolves a review item.
     *
     * <p>Depending on the action, this may link a source record to an existing
     * transaction, mark an ambiguous match as a new transaction, or dismiss
     * the question.
     */
    @PostMapping("/review/{id}/resolve")
    public ApiDtos.ReviewItemDto resolve(@PathVariable String id,
                                         @Valid @RequestBody ApiDtos.ResolveReviewRequest request) {
        ReviewItem item = reviewService.find(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown review item: " + id));

        String action = request.action().toUpperCase();

        switch (action) {
            case "DISMISS" -> reviewService.dismiss(id,
                    request.note() == null ? "dismissed by user" : request.note());

            case "LINK" -> {
                if (request.transactionId() == null) {
                    throw new IllegalArgumentException(
                            "transactionId is required for the LINK action");
                }
                // Verify the target transaction exists, then mark resolved.
                transactionService.require(request.transactionId());
                reviewService.resolve(id, "user linked to transaction " + request.transactionId());
            }

            case "CREATE" -> reviewService.resolve(id,
                    "accepted as a separate transaction by user");

            default -> throw new IllegalArgumentException(
                    "unknown action: " + action + ". Valid: LINK, CREATE, DISMISS");
        }

        return toReviewDto(reviewService.find(id).orElseThrow());
    }

    private ApiDtos.ReviewItemDto toReviewDto(ReviewItem item) {
        List<ApiDtos.CandidateDto> candidates = reviewService.readCandidates(item).stream()
                .map(c -> new ApiDtos.CandidateDto(c.transactionId(), c.score(), c.rationale(),
                        c.amountMinor(), c.currency(), c.occurredOn(), c.merchant()))
                .toList();

        ApiDtos.SourceRecordDto sourceDto = item.getSourceRecordId() == null ? null
                : sourceRecords.findById(item.getSourceRecordId())
                        .map(ApiDtos::toSourceRecordDto)
                        .orElse(null);

        return ApiDtos.toReviewDto(item, candidates, sourceDto);
    }
}
