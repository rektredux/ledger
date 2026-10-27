package com.ledger.provider.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.ingest.RawObservation;
import com.ledger.source.TransactionSourceProvider.ProviderAuthException;
import com.ledger.source.TransactionSourceProvider.ProviderTransientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin client over the official Gmail REST v1 API.
 *
 * <p>Uses the JDK's {@link HttpClient} against the documented endpoints rather
 * than the {@code google-api-client} SDK. The connector needs four calls —
 * profile, list, get, history — and the SDK would add roughly fifteen
 * transitive dependencies to provide them. For an application handling
 * financial data, a dependency surface small enough to audit is worth more than
 * the convenience.
 *
 * <h2>Error mapping</h2>
 * HTTP status is translated into the two exception types the provider contract
 * defines, because the right response differs completely:
 * <ul>
 *   <li>401 / 403 → {@link ProviderAuthException}: the grant is gone. Stop and
 *       ask the user to reconnect; retrying cannot help.</li>
 *   <li>429 / 5xx → {@link ProviderTransientException}: retry with backoff.</li>
 *   <li>404 on a message → treated as "vanished", not an error: the user may
 *       have deleted it between the list and the fetch.</li>
 * </ul>
 */
@Component
public class GmailApiClient {

    private static final Logger log = LoggerFactory.getLogger(GmailApiClient.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final GmailProperties properties;

    public GmailApiClient(ObjectMapper objectMapper, GmailProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                // Google's token endpoint redirects in some error cases; never
                // follow a redirect automatically with an Authorization header
                // attached.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** A page of message ids from {@code users.messages.list}. */
    public record MessageIdPage(List<String> messageIds, String nextPageToken) {
        public boolean hasMore() {
            return nextPageToken != null && !nextPageToken.isBlank();
        }
    }

    /** The authenticated mailbox's address and current history id. */
    public record Profile(String emailAddress, String historyId) {
    }

    /**
     * The mailbox address and history id.
     *
     * <p>The history id is captured at connect time and becomes the initial
     * incremental cursor, so that everything arriving after authorization is
     * picked up by history rather than by repeatedly re-searching.
     */
    public Profile fetchProfile(String accessToken) throws ProviderAuthException, ProviderTransientException {
        JsonNode body = get(accessToken, properties.getApiBaseUrl() + "/gmail/v1/users/me/profile");
        return new Profile(body.path("emailAddress").asText(null), body.path("historyId").asText(null));
    }

    /**
     * Searches the mailbox.
     *
     * <p>The query is applied server-side, so messages that do not look
     * financial are never transferred. {@code after:}/{@code before:} bound the
     * window for a backfill.
     */
    public MessageIdPage listMessages(String accessToken, String query, int pageSize, String pageToken)
            throws ProviderAuthException, ProviderTransientException {

        StringBuilder url = new StringBuilder(properties.getApiBaseUrl())
                .append("/gmail/v1/users/me/messages")
                .append("?maxResults=").append(Math.clamp(pageSize, 1, 500));
        if (query != null && !query.isBlank()) {
            url.append("&q=").append(encode(query));
        }
        if (pageToken != null && !pageToken.isBlank()) {
            url.append("&pageToken=").append(encode(pageToken));
        }

        JsonNode body = get(accessToken, url.toString());
        List<String> ids = new ArrayList<>();
        for (JsonNode message : body.path("messages")) {
            String id = message.path("id").asText(null);
            if (id != null) {
                ids.add(id);
            }
        }
        return new MessageIdPage(ids, body.path("nextPageToken").asText(null));
    }

    /**
     * Message ids added since a history id, via {@code users.history.list}.
     *
     * <p>This is the incremental path and is far cheaper than re-searching. It
     * has one sharp edge: Google expires history older than roughly a week, and
     * returns 404 when the stored id is too old. That is reported as an empty
     * optional so the caller can fall back to a search rather than treating a
     * normal, expected condition as a failure.
     */
    public Optional<MessageIdPage> listHistory(String accessToken, String startHistoryId,
                                               int pageSize, String pageToken)
            throws ProviderAuthException, ProviderTransientException {

        StringBuilder url = new StringBuilder(properties.getApiBaseUrl())
                .append("/gmail/v1/users/me/history")
                .append("?startHistoryId=").append(encode(startHistoryId))
                .append("&historyTypes=messageAdded")
                .append("&maxResults=").append(Math.clamp(pageSize, 1, 500));
        if (pageToken != null && !pageToken.isBlank()) {
            url.append("&pageToken=").append(encode(pageToken));
        }

        HttpResponse<String> response = send(accessToken, url.toString());
        if (response.statusCode() == 404) {
            log.info("Gmail history id has expired; falling back to a search-based sync");
            return Optional.empty();
        }
        JsonNode body = parse(checkStatus(response));

        List<String> ids = new ArrayList<>();
        for (JsonNode entry : body.path("history")) {
            for (JsonNode added : entry.path("messagesAdded")) {
                String id = added.path("message").path("id").asText(null);
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return Optional.of(new MessageIdPage(ids, body.path("nextPageToken").asText(null)));
    }

    /**
     * Fetches one message and converts it to a provider-neutral observation.
     *
     * @return empty when the message no longer exists
     */
    public Optional<RawObservation> fetchMessage(String accessToken, String messageId)
            throws ProviderAuthException, ProviderTransientException {

        String url = properties.getApiBaseUrl() + "/gmail/v1/users/me/messages/"
                + encode(messageId) + "?format=full";

        HttpResponse<String> response = send(accessToken, url);
        if (response.statusCode() == 404) {
            // Deleted between listing and fetching. Normal, not an error.
            return Optional.empty();
        }
        JsonNode message = parse(checkStatus(response));
        return Optional.of(toObservation(messageId, message));
    }

    /** Converts a Gmail message resource into a {@link RawObservation}. */
    RawObservation toObservation(String messageId, JsonNode message) {
        JsonNode payload = message.path("payload");
        Map<String, String> headers = readHeaders(payload);

        String from = headers.getOrDefault("from", null);
        String subject = headers.getOrDefault("subject", null);

        // internalDate is epoch milliseconds and is the delivery time Gmail
        // recorded. Preferred over the Date header, which is set by the sender
        // and is sometimes wrong or absent.
        Instant receivedAt = null;
        String internalDate = message.path("internalDate").asText(null);
        if (internalDate != null && !internalDate.isBlank()) {
            try {
                receivedAt = Instant.ofEpochMilli(Long.parseLong(internalDate));
            } catch (NumberFormatException e) {
                receivedAt = null;
            }
        }

        String body = extractBody(payload);

        Map<String, String> metadata = new HashMap<>();
        String labels = String.join(",", readLabels(message));
        if (!labels.isBlank()) {
            metadata.put("labels", labels);
        }
        String threadId = message.path("threadId").asText(null);
        if (threadId != null) {
            metadata.put("threadId", threadId);
        }

        return new RawObservation(messageId, from, subject, receivedAt, body, metadata);
    }

    private static Map<String, String> readHeaders(JsonNode payload) {
        Map<String, String> headers = new HashMap<>();
        for (JsonNode header : payload.path("headers")) {
            String name = header.path("name").asText(null);
            String value = header.path("value").asText(null);
            if (name != null && value != null) {
                headers.put(name.toLowerCase(), value);
            }
        }
        return headers;
    }

    private static List<String> readLabels(JsonNode message) {
        List<String> labels = new ArrayList<>();
        for (JsonNode label : message.path("labelIds")) {
            labels.add(label.asText());
        }
        return labels;
    }

    /**
     * Extracts readable text from a MIME tree.
     *
     * <p>Walks the parts depth-first preferring {@code text/plain}, and falls
     * back to {@code text/html} with tags stripped. Bank alerts are frequently
     * HTML-only, so the fallback is not optional — without it a large share of
     * real alerts would yield an empty body and fail to parse.
     */
    String extractBody(JsonNode payload) {
        StringBuilder plain = new StringBuilder();
        StringBuilder html = new StringBuilder();
        collectParts(payload, plain, html, 0);

        if (!plain.isEmpty()) {
            return plain.toString();
        }
        if (!html.isEmpty()) {
            return stripHtml(html.toString());
        }
        return "";
    }

    /** Depth is bounded; deeply nested multipart mail is pathological. */
    private void collectParts(JsonNode part, StringBuilder plain, StringBuilder html, int depth) {
        if (part == null || part.isMissingNode() || depth > 12) {
            return;
        }
        String mimeType = part.path("mimeType").asText("");
        String data = part.path("body").path("data").asText(null);

        if (data != null && !data.isBlank()) {
            String decoded = decodeBase64Url(data);
            if (mimeType.startsWith("text/plain")) {
                plain.append(decoded).append('\n');
            } else if (mimeType.startsWith("text/html")) {
                html.append(decoded).append('\n');
            }
        }
        for (JsonNode child : part.path("parts")) {
            collectParts(child, plain, html, depth + 1);
        }
    }

    /** Gmail encodes bodies base64url without padding. */
    static String decodeBase64Url(String data) {
        try {
            return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A body we cannot decode becomes empty text, which the pipeline
            // reports as a parse failure rather than crashing the sync.
            return "";
        }
    }

    /**
     * Reduces HTML to text.
     *
     * <p>Deliberately minimal: drop script and style content, convert block
     * boundaries to newlines, strip remaining tags, decode the handful of
     * entities that matter for currency and spacing. An HTML parser dependency
     * would be a poor trade here — the goal is a string for regex extraction,
     * not a faithful DOM.
     */
    static String stripHtml(String html) {
        String text = html;
        text = text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</(p|div|tr|li|h[1-6]|table)>", "\n");
        text = text.replaceAll("(?i)</t[dh]>", " ");
        text = text.replaceAll("(?s)<!--.*?-->", " ");
        text = text.replaceAll("<[^>]+>", " ");

        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&rupee;", "₹")
                .replace("&#8377;", "₹")
                .replace("&#x20B9;", "₹");

        // Numeric entities for anything else.
        text = text.replaceAll("&#\\d+;", " ");
        // Collapse whitespace but keep line structure: several patterns in the
        // extractor are line-oriented.
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        text = text.replaceAll("\\n\\s*\\n\\s*", "\n");
        return text.trim();
    }

    // --- HTTP plumbing ------------------------------------------------------

    private JsonNode get(String accessToken, String url)
            throws ProviderAuthException, ProviderTransientException {
        return parse(checkStatus(send(accessToken, url)));
    }

    private HttpResponse<String> send(String accessToken, String url)
            throws ProviderTransientException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProviderTransientException("network error calling the Gmail API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderTransientException("interrupted while calling the Gmail API", e);
        }
    }

    private HttpResponse<String> checkStatus(HttpResponse<String> response)
            throws ProviderAuthException, ProviderTransientException {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response;
        }
        if (status == 401) {
            throw new ProviderAuthException("Gmail rejected the access token (401)");
        }
        if (status == 403) {
            // 403 covers both "scope revoked" and "rate limited". Google
            // distinguishes them only in the error reason, so inspect it:
            // misclassifying a rate limit as a revoked grant would needlessly
            // ask the user to reconnect.
            String reason = extractErrorReason(response.body());
            if (reason.contains("rateLimitExceeded") || reason.contains("userRateLimitExceeded")
                    || reason.contains("quotaExceeded") || reason.contains("backendError")) {
                throw new ProviderTransientException("Gmail rate limit or quota exceeded (403: " + reason + ")");
            }
            throw new ProviderAuthException("Gmail denied access (403: " + reason + ")");
        }
        if (status == 429 || status >= 500) {
            throw new ProviderTransientException("Gmail API returned " + status);
        }
        throw new ProviderTransientException("Gmail API returned an unexpected " + status);
    }

    /** Pulls the error reason out without echoing the whole response body. */
    private String extractErrorReason(String body) {
        if (body == null || body.isBlank()) {
            return "no detail";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode errors = root.path("error").path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                return errors.get(0).path("reason").asText("unknown");
            }
            return root.path("error").path("status").asText("unknown");
        } catch (IOException e) {
            return "unparseable error response";
        }
    }

    private JsonNode parse(HttpResponse<String> response) throws ProviderTransientException {
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new ProviderTransientException("could not parse the Gmail API response", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
