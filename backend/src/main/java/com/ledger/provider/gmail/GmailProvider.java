package com.ledger.provider.gmail;

import com.ledger.ingest.RawObservation;
import com.ledger.source.ProviderConnection;
import com.ledger.source.ProviderType;
import com.ledger.source.TransactionSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Gmail source provider.
 *
 * <p>Fetches financial mail through the official Gmail REST v1 API and hands it
 * to the ingestion pipeline. Everything about parsing, categorisation and
 * deduplication lives elsewhere; this class is concerned only with getting
 * messages out of Gmail reliably and resumably.
 *
 * <h2>Two fetch strategies</h2>
 * <ul>
 *   <li><b>Search</b> ({@code users.messages.list}) with a date window, used
 *       for the historical backfill and whenever the incremental cursor is
 *       unusable. Walks backwards a month at a time so a long import makes
 *       visible, resumable progress.</li>
 *   <li><b>History</b> ({@code users.history.list}) from the stored
 *       {@code historyId}, used for routine incremental syncs. Far cheaper:
 *       Gmail returns only what changed instead of re-matching the query.</li>
 * </ul>
 *
 * <p>Google expires history ids after about a week. That is an expected
 * condition, not a failure: when it happens the provider falls back to a
 * search over the recent window and re-establishes a fresh cursor. Because
 * ingestion is idempotent, re-reading messages during that fallback cannot
 * create duplicates.
 *
 * <h2>Cursor format</h2>
 * The cursor is the mailbox {@code historyId} for incremental mode, or
 * {@code "search:<yyyy-MM-dd>"} while backfilling, which records how far back
 * the import has reached. One string, interpreted by whichever mode is active.
 */
@Component
public class GmailProvider implements TransactionSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(GmailProvider.class);

    private static final String SEARCH_CURSOR_PREFIX = "search:";
    private static final DateTimeFormatter GMAIL_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final ZoneId LOCAL_ZONE = ZoneId.systemDefault();

    /**
     * How far back one backfill page reaches. A month at a time keeps each
     * request bounded and lets the watermark advance visibly, so a user
     * importing a year of history sees progress rather than one long stall.
     */
    private static final int BACKFILL_WINDOW_DAYS = 31;

    private final GmailApiClient api;
    private final GmailOAuthService oauth;
    private final GmailProperties properties;

    public GmailProvider(GmailApiClient api, GmailOAuthService oauth, GmailProperties properties) {
        this.api = api;
        this.oauth = oauth;
        this.properties = properties;
    }

    @Override
    public ProviderType type() {
        return ProviderType.GMAIL;
    }

    @Override
    public String displayName() {
        return "Gmail";
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public boolean requiresOAuth() {
        return true;
    }

    @Override
    public Optional<String> authorizationUrl(String state) {
        return oauth.authorizationUrl(state);
    }

    @Override
    public FetchPage fetch(ProviderConnection connection, FetchRequest request)
            throws ProviderAuthException, ProviderTransientException {

        String accessToken = oauth.accessTokenFor(connection);

        boolean backfilling = !connection.isBackfillComplete();
        if (backfilling) {
            return fetchBySearch(accessToken, connection, request);
        }

        String cursor = request.cursor();
        if (cursor == null || cursor.isBlank() || cursor.startsWith(SEARCH_CURSOR_PREFIX)) {
            // No usable history cursor yet: establish one from the profile and
            // sweep the recent window by search so nothing between the last
            // run and now is missed.
            return fetchBySearch(accessToken, connection, request);
        }

        Optional<GmailApiClient.MessageIdPage> history =
                api.listHistory(accessToken, cursor, request.pageSize(), null);

        if (history.isEmpty()) {
            // The history id expired. Re-establish by search; idempotent
            // ingestion makes the overlap harmless.
            log.info("Gmail history cursor expired; re-establishing by search");
            return fetchBySearch(accessToken, connection, request);
        }

        List<RawObservation> observations = fetchMessages(accessToken, history.get().messageIds());

        // The profile's current historyId is the correct next cursor: it
        // accounts for changes the history page did not enumerate.
        String nextCursor = api.fetchProfile(accessToken).historyId();
        return new FetchPage(observations, nextCursor == null ? cursor : nextCursor,
                history.get().hasMore(), null);
    }

    /**
     * Fetches by search over a bounded date window.
     *
     * <p>During a backfill the window steps backwards from whatever the
     * watermark has reached; once caught up it covers the recent period. The
     * Gmail query is combined with {@code after:}/{@code before:} so filtering
     * stays server-side and unrelated mail is never downloaded.
     */
    private FetchPage fetchBySearch(String accessToken, ProviderConnection connection,
                                    FetchRequest request)
            throws ProviderAuthException, ProviderTransientException {

        LocalDate windowEnd = connection.getBackfillThrough() != null
                ? connection.getBackfillThrough()
                : (request.until() == null ? LocalDate.now() : request.until());
        LocalDate floor = request.since() == null
                ? LocalDate.now().minusYears(1)
                : request.since();

        LocalDate windowStart = windowEnd.minusDays(BACKFILL_WINDOW_DAYS);
        if (windowStart.isBefore(floor)) {
            windowStart = floor;
        }

        if (!windowStart.isBefore(windowEnd) && connection.getBackfillThrough() != null) {
            // The window has collapsed: the backfill has reached the floor.
            String nextCursor = api.fetchProfile(accessToken).historyId();
            return new FetchPage(List.of(), nextCursor, false, floor);
        }

        // Gmail's after:/before: are exclusive at the edges in practice, so the
        // bounds are widened by a day to avoid dropping boundary messages. The
        // overlap is harmless because ingestion is idempotent.
        String query = properties.getQuery()
                + " after:" + GMAIL_DATE.format(windowStart.minusDays(1))
                + " before:" + GMAIL_DATE.format(windowEnd.plusDays(1));

        GmailApiClient.MessageIdPage page =
                api.listMessages(accessToken, query, request.pageSize(), null);
        List<RawObservation> observations = fetchMessages(accessToken, page.messageIds());

        boolean reachedFloor = !windowStart.isAfter(floor);
        String nextCursor;
        if (page.hasMore()) {
            // More in this window: stay on it, paginating by page token on the
            // next call is not possible through this interface, so the window
            // is kept and the already-seen ids are skipped by the idempotency
            // check. Progress still comes from the watermark.
            nextCursor = SEARCH_CURSOR_PREFIX + windowStart;
        } else if (reachedFloor) {
            // Backfill done: switch to a real history cursor.
            nextCursor = api.fetchProfile(accessToken).historyId();
        } else {
            nextCursor = SEARCH_CURSOR_PREFIX + windowStart;
        }

        return new FetchPage(observations, nextCursor, !reachedFloor, windowStart);
    }

    /**
     * Fetches each message body.
     *
     * <p>A message that has disappeared between listing and fetching is simply
     * skipped. An auth failure is rethrown immediately — continuing would
     * produce a page of failures for no reason.
     */
    private List<RawObservation> fetchMessages(String accessToken, List<String> messageIds)
            throws ProviderAuthException, ProviderTransientException {

        List<RawObservation> observations = new ArrayList<>(messageIds.size());
        for (String messageId : messageIds) {
            Optional<RawObservation> observation = api.fetchMessage(accessToken, messageId);
            observation.ifPresent(observations::add);
        }
        // Oldest first, so that when several sightings of one purchase arrive in
        // the same page the earliest becomes the canonical transaction and the
        // later ones link to it.
        observations.sort((a, b) -> {
            if (a.receivedAt() == null || b.receivedAt() == null) {
                return 0;
            }
            return a.receivedAt().compareTo(b.receivedAt());
        });
        return observations;
    }

    @Override
    public void disconnect(ProviderConnection connection) {
        boolean revoked = oauth.revoke(connection);
        if (!revoked) {
            log.warn("Gmail token could not be confirmed as revoked with Google. "
                    + "Local credentials have been cleared; revoke manually at "
                    + "https://myaccount.google.com/permissions");
        }
    }

    /**
     * Completes the OAuth callback: exchanges the code, stores encrypted
     * tokens, records the mailbox address and sets the initial cursor.
     *
     * <p>The caller is responsible for saving the connection.
     */
    public void completeAuthorization(ProviderConnection connection, String code)
            throws ProviderAuthException, ProviderTransientException {

        GmailOAuthService.TokenSet tokens = oauth.exchangeCode(code);

        // Verify Google actually granted the scope asked for. A user can
        // deselect permissions on the consent screen, and discovering that at
        // the first fetch would look like a mysterious failure.
        if (tokens.scope() != null && !tokens.scope().contains("gmail.readonly")) {
            throw new ProviderAuthException(
                    "the Gmail read-only permission was not granted; connect again and accept it");
        }

        oauth.applyTo(connection, tokens);

        GmailApiClient.Profile profile = api.fetchProfile(tokens.accessToken());
        connection.setDisplayName(profile.emailAddress());
        connection.setStatus(ProviderConnection.Status.CONNECTED);

        // Start a backfill from today's date and walk backwards. The history id
        // is captured but the backfill runs by search first; the cursor becomes
        // a history id once the backfill completes.
        connection.setBackfillComplete(false);
        connection.setBackfillThrough(LocalDate.now());
        connection.setSyncCursor(SEARCH_CURSOR_PREFIX + LocalDate.now());
        connection.setConsecutiveFailures(0);
        connection.setLastError(null);
        connection.setSyncEnabled(true);
    }
}
