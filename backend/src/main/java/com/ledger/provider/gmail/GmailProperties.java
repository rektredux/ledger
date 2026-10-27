package com.ledger.provider.gmail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Gmail connector configuration.
 *
 * <p>The client id and secret come from a Google Cloud OAuth client the user
 * creates themselves (see the README). They are read from the environment and
 * never committed. Until both are present the connector reports itself as not
 * configured and the UI shows setup instructions instead of a connect button
 * that cannot work.
 */
@Component
@ConfigurationProperties(prefix = "ledger.gmail")
public class GmailProperties {

    /**
     * The only scope requested.
     *
     * <p>{@code gmail.readonly} is the narrowest scope that can actually do the
     * job. The narrower {@code gmail.metadata} returns headers but not bodies,
     * and the amount, merchant and reference all live in the body, so it cannot
     * support parsing. Nothing broader is requested: no modify, no send, no
     * delete. The app never alters or removes a message.
     */
    public static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

    private String clientId = "";
    private String clientSecret = "";
    private String redirectUri = "http://127.0.0.1:8080/api/sources/gmail/callback";

    /**
     * Gmail search query restricting which messages are examined.
     *
     * <p>This is a privacy control as much as an efficiency one. Without it the
     * connector would read every message in the mailbox; with it, only mail
     * that looks financial is fetched at all. Ordinary personal correspondence
     * is never downloaded, let alone parsed.
     *
     * <p>Overridable so a user whose bank is not covered can widen it, or a
     * user who wants a tighter filter can narrow it to specific senders.
     */
    private String query = defaultQuery();

    /** Base URL of the Gmail API; overridden in tests to point at a stub. */
    private String apiBaseUrl = "https://gmail.googleapis.com";

    /** Base URL of Google's OAuth token endpoint; overridden in tests. */
    private String tokenUrl = "https://oauth2.googleapis.com/token";

    private String authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth";

    private String revokeUrl = "https://oauth2.googleapis.com/revoke";

    /**
     * The shipped default query.
     *
     * <p>Matches transaction-alert wording and the sender patterns banks and
     * merchants use, and excludes spam and the user's own sent mail. Written as
     * a single Gmail search expression because filtering server-side is what
     * keeps unrelated mail from ever being downloaded.
     */
    private static String defaultQuery() {
        return "("
                + "subject:(debited OR credited OR \"transaction alert\" OR \"txn alert\" "
                + "OR \"order confirmed\" OR \"payment received\" OR \"payment successful\" "
                + "OR receipt OR invoice OR refund OR reversed OR UPI OR \"credit card\")"
                + " OR from:(alerts OR alert OR noreply OR no-reply OR donotreply OR "
                + "statements OR transaction OR txn OR orders OR billing OR receipts)"
                + ") -in:spam -in:trash -from:me";
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getAuthorizationUrl() {
        return authorizationUrl;
    }

    public void setAuthorizationUrl(String authorizationUrl) {
        this.authorizationUrl = authorizationUrl;
    }

    public String getRevokeUrl() {
        return revokeUrl;
    }

    public void setRevokeUrl(String revokeUrl) {
        this.revokeUrl = revokeUrl;
    }
}
