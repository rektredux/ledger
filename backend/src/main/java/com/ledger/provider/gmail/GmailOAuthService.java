package com.ledger.provider.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.security.TokenCipher;
import com.ledger.source.ProviderConnection;
import com.ledger.source.TransactionSourceProvider.ProviderAuthException;
import com.ledger.source.TransactionSourceProvider.ProviderTransientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Google OAuth 2.0 authorization code flow for Gmail.
 *
 * <p>Implements the standard three legs: send the user to Google's consent
 * screen, exchange the returned code for tokens, and refresh the access token
 * as it expires. Plus revocation, which matters as much as the rest — a
 * disconnect that only forgets the token locally leaves a live grant on the
 * user's Google account.
 *
 * <h2>The password question</h2>
 * There is no code path here, or anywhere in this application, that accepts a
 * Gmail password. The user authenticates with Google directly; this application
 * only ever sees an authorization code and the tokens exchanged for it. That is
 * the entire reason to use OAuth, and it is why the connector cannot be
 * "simplified" into an IMAP login.
 *
 * <h2>Token handling</h2>
 * Plaintext tokens exist only as local variables and method returns. Anything
 * written to the database goes through {@link TokenCipher} first, and no token
 * is ever logged, including at debug level.
 */
@Service
public class GmailOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GmailOAuthService.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final GmailProperties properties;
    private final TokenCipher tokenCipher;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    public GmailOAuthService(GmailProperties properties,
                             TokenCipher tokenCipher,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokenCipher = tokenCipher;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Tokens as returned by Google. Never persisted in this form. */
    public record TokenSet(String accessToken, String refreshToken, Instant expiresAt, String scope) {
    }

    /**
     * An opaque random value tying a callback to the request that started it.
     *
     * <p>Required to prevent an attacker-initiated callback from attaching
     * their mailbox to this installation. The caller stores it and compares on
     * return.
     */
    public String newState() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The Google consent URL to send the user to.
     *
     * <p>Parameters worth noting:
     * <ul>
     *   <li>{@code access_type=offline} — required to receive a refresh token.
     *       Without it sync would stop working an hour after connecting.</li>
     *   <li>{@code prompt=consent} — forces Google to re-issue a refresh token.
     *       Google only returns one on the first authorization for a
     *       client/user pair, so a reconnect after the token was lost would
     *       otherwise yield an access token with no way to renew it.</li>
     *   <li>{@code include_granted_scopes=false} — do not silently accumulate
     *       scopes previously granted to this client.</li>
     * </ul>
     */
    public Optional<String> authorizationUrl(String state) {
        if (!properties.isConfigured()) {
            return Optional.empty();
        }
        String url = properties.getAuthorizationUrl()
                + "?client_id=" + encode(properties.getClientId())
                + "&redirect_uri=" + encode(properties.getRedirectUri())
                + "&response_type=code"
                + "&scope=" + encode(GmailProperties.SCOPE)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=false"
                + "&state=" + encode(state);
        return Optional.of(url);
    }

    /**
     * Exchanges an authorization code for tokens.
     *
     * @throws ProviderAuthException when Google rejects the exchange, which
     *     normally means the code was reused, expired, or the redirect URI does
     *     not match the one registered in the Cloud console
     */
    public TokenSet exchangeCode(String code) throws ProviderAuthException, ProviderTransientException {
        if (!properties.isConfigured()) {
            throw new ProviderAuthException("Gmail OAuth client credentials are not configured");
        }
        Map<String, String> form = Map.of(
                "code", code,
                "client_id", properties.getClientId(),
                "client_secret", properties.getClientSecret(),
                "redirect_uri", properties.getRedirectUri(),
                "grant_type", "authorization_code");

        JsonNode body = postForm(properties.getTokenUrl(), form);

        String refreshToken = body.path("refresh_token").asText(null);
        if (refreshToken == null) {
            // Without a refresh token sync dies silently in an hour. Fail now,
            // with an actionable message, rather than appearing to succeed.
            throw new ProviderAuthException(
                    "Google did not return a refresh token. Remove this app's access at "
                            + "https://myaccount.google.com/permissions and connect again.");
        }
        return toTokenSet(body, refreshToken);
    }

    /**
     * Obtains a fresh access token using the stored refresh token.
     *
     * <p>Google does not return a new refresh token on refresh, so the stored
     * one is retained.
     *
     * @throws ProviderAuthException when the refresh token has been revoked or
     *     expired. The caller marks the connection {@code NEEDS_REAUTH}; no
     *     amount of retrying will recover it.
     */
    public TokenSet refresh(ProviderConnection connection)
            throws ProviderAuthException, ProviderTransientException {

        if (connection.getRefreshTokenEnc() == null) {
            throw new ProviderAuthException("no refresh token is stored; reconnect the mailbox");
        }
        String refreshToken;
        try {
            refreshToken = tokenCipher.decrypt(connection.getRefreshTokenEnc());
        } catch (TokenCipher.TokenCipherException e) {
            throw new ProviderAuthException(
                    "the stored refresh token could not be decrypted; reconnect the mailbox", e);
        }

        Map<String, String> form = Map.of(
                "refresh_token", refreshToken,
                "client_id", properties.getClientId(),
                "client_secret", properties.getClientSecret(),
                "grant_type", "refresh_token");

        JsonNode body = postForm(properties.getTokenUrl(), form);
        return toTokenSet(body, refreshToken);
    }

    /**
     * Returns a usable access token, refreshing first if needed, and persists
     * any new token onto the connection.
     *
     * <p>The caller is responsible for saving the connection afterwards.
     */
    public String accessTokenFor(ProviderConnection connection)
            throws ProviderAuthException, ProviderTransientException {

        if (!connection.needsAccessTokenRefresh()) {
            try {
                return tokenCipher.decrypt(connection.getAccessTokenEnc());
            } catch (TokenCipher.TokenCipherException e) {
                // Fall through to a refresh: a corrupt access token is
                // recoverable if the refresh token is intact.
                log.info("Stored access token could not be decrypted; refreshing");
            }
        }
        TokenSet refreshed = refresh(connection);
        applyTo(connection, refreshed);
        return refreshed.accessToken();
    }

    /** Writes an encrypted token set onto a connection. */
    public void applyTo(ProviderConnection connection, TokenSet tokens) {
        connection.setAccessTokenEnc(tokenCipher.encrypt(tokens.accessToken()));
        if (tokens.refreshToken() != null) {
            connection.setRefreshTokenEnc(tokenCipher.encrypt(tokens.refreshToken()));
        }
        connection.setAccessTokenExpiresAt(tokens.expiresAt());
        if (tokens.scope() != null) {
            connection.setGrantedScopes(tokens.scope());
        }
    }

    /**
     * Revokes the grant with Google and clears local credentials.
     *
     * <p>Local credentials are cleared even when the remote revocation fails,
     * because the user asked to disconnect and must not be left connected. The
     * failure is reported so they can revoke manually at
     * {@code myaccount.google.com/permissions} — which the UI tells them to do
     * regardless, since that page is the authoritative view of what has access.
     *
     * @return true when Google confirmed the revocation
     */
    public boolean revoke(ProviderConnection connection) {
        boolean revoked = false;
        String token = null;
        try {
            String encrypted = connection.getRefreshTokenEnc() != null
                    ? connection.getRefreshTokenEnc()
                    : connection.getAccessTokenEnc();
            if (encrypted != null) {
                token = tokenCipher.decrypt(encrypted);
            }
        } catch (TokenCipher.TokenCipherException e) {
            log.warn("Could not decrypt the stored token in order to revoke it");
        }

        if (token != null) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getRevokeUrl()))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("token=" + encode(token)))
                        .build();
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                // 200 means revoked; 400 usually means it was already invalid,
                // which is the same end state from the user's point of view.
                revoked = response.statusCode() == 200 || response.statusCode() == 400;
            } catch (IOException e) {
                log.warn("Could not reach Google to revoke the token: {}", e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        connection.clearTokens();
        connection.setStatus(ProviderConnection.Status.DISCONNECTED);
        connection.setSyncCursor(null);
        connection.setBackfillComplete(false);
        connection.setBackfillThrough(null);
        return revoked;
    }

    private TokenSet toTokenSet(JsonNode body, String refreshToken) {
        String accessToken = body.path("access_token").asText(null);
        long expiresIn = body.path("expires_in").asLong(3600L);
        String scope = body.path("scope").asText(null);
        return new TokenSet(accessToken, refreshToken,
                Instant.now().plusSeconds(expiresIn), scope);
    }

    private JsonNode postForm(String url, Map<String, String> form)
            throws ProviderAuthException, ProviderTransientException {

        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encoded.toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProviderTransientException("could not reach Google's token endpoint", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderTransientException("interrupted during the token request", e);
        }

        if (response.statusCode() == 400 || response.statusCode() == 401) {
            // The body of a token error names the problem ("invalid_grant")
            // without containing the token itself, so it is safe and useful to
            // surface.
            throw new ProviderAuthException("Google rejected the token request: "
                    + extractError(response.body()));
        }
        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            throw new ProviderTransientException(
                    "Google's token endpoint returned " + response.statusCode());
        }
        if (response.statusCode() != 200) {
            throw new ProviderTransientException(
                    "unexpected response " + response.statusCode() + " from Google's token endpoint");
        }

        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new ProviderTransientException("could not parse Google's token response", e);
        }
    }

    private String extractError(String body) {
        if (body == null || body.isBlank()) {
            return "no detail";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String error = root.path("error").asText("unknown");
            String description = root.path("error_description").asText(null);
            return description == null ? error : error + " (" + description + ")";
        } catch (IOException e) {
            return "unparseable error response";
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
