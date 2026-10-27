package com.ledger.source;

import com.ledger.common.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The user's authorization for one provider, plus everything needed to resume
 * synchronization where it left off.
 *
 * <p>All synchronization state lives in this row rather than in memory, which
 * is what makes sync survive a restart: the backend can be killed mid-import
 * and the next poll continues from {@link #syncCursor} and
 * {@link #backfillThrough} instead of re-reading the mailbox from scratch.
 *
 * <h2>Token handling</h2>
 * Access and refresh tokens are stored encrypted (AES-GCM, see
 * {@code TokenCipher}) in {@link #accessTokenEnc} and
 * {@link #refreshTokenEnc}. Plaintext tokens exist only as local variables
 * while a request is in flight and are never logged. There is no field for a
 * password anywhere in this class or this application: OAuth is the only
 * credential path, and the user's Gmail password is never requested or seen.
 */
@Entity
@Table(name = "provider_connection")
public class ProviderConnection {

    public enum Status {
        /**
         * The operator has not supplied the OAuth client credentials this
         * provider needs, so it cannot be connected at all. Distinct from
         * DISCONNECTED: the user cannot fix this from the UI, the deployment
         * needs configuring first.
         */
        NOT_CONFIGURED,

        /** Configured and available, but the user has not authorized it. */
        DISCONNECTED,

        /** Authorized and syncing. */
        CONNECTED,

        /**
         * The refresh token was rejected (revoked, expired, or password
         * changed). Sync is paused and the UI asks the user to reconnect
         * rather than silently reporting stale data as current.
         */
        NEEDS_REAUTH,

        /** Repeated failures that are not an auth problem. */
        ERROR
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id = Ids.newId();

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private ProviderType provider;

    /** The connected mailbox address, for display. Masked in logs. */
    @Column(name = "display_name")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.DISCONNECTED;

    @Column(name = "access_token_enc")
    private String accessTokenEnc;

    @Column(name = "refresh_token_enc")
    private String refreshTokenEnc;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    /** The scopes Google actually granted, which may be narrower than requested. */
    @Column(name = "granted_scopes")
    private String grantedScopes;

    /**
     * Provider-defined incremental cursor. For Gmail this is the mailbox
     * {@code historyId}: everything after it is new. Advanced only after the
     * records from a page have been durably stored, so a crash re-reads a page
     * rather than skipping it.
     */
    @Column(name = "sync_cursor")
    private String syncCursor;

    /**
     * The oldest date the historical backfill has reached. Lets a long import
     * resume after a restart instead of starting over.
     */
    @Column(name = "backfill_through")
    private LocalDate backfillThrough;

    @Column(name = "backfill_complete", nullable = false)
    private boolean backfillComplete;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** Redacted failure message, shown on the Data Sources screen. */
    @Column(name = "last_error")
    private String lastError;

    /** Drives exponential backoff and the health indicator. */
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "sync_enabled", nullable = false)
    private boolean syncEnabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProviderConnection() {
        // for JPA
    }

    public ProviderConnection(ProviderType provider) {
        this.provider = provider;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** True when this connection should be polled by the scheduler. */
    public boolean isSyncable() {
        return syncEnabled && status == Status.CONNECTED;
    }

    /**
     * True when the stored access token is absent or within 60 seconds of
     * expiry. The margin avoids a token expiring mid-request.
     */
    public boolean needsAccessTokenRefresh() {
        return accessTokenEnc == null
                || accessTokenExpiresAt == null
                || accessTokenExpiresAt.isBefore(Instant.now().plusSeconds(60));
    }

    public void recordSuccess(String newCursor) {
        this.syncCursor = newCursor;
        this.lastSuccessAt = Instant.now();
        this.lastAttemptAt = this.lastSuccessAt;
        this.consecutiveFailures = 0;
        this.lastError = null;
        if (this.status == Status.ERROR) {
            this.status = Status.CONNECTED;
        }
    }

    public void recordFailure(String redactedMessage) {
        this.lastAttemptAt = Instant.now();
        this.consecutiveFailures++;
        this.lastError = redactedMessage;
    }

    /** Clears all credential material. Used on disconnect. */
    public void clearTokens() {
        this.accessTokenEnc = null;
        this.refreshTokenEnc = null;
        this.accessTokenExpiresAt = null;
        this.grantedScopes = null;
    }

    // --- accessors ----------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ProviderType getProvider() {
        return provider;
    }

    public void setProvider(ProviderType provider) {
        this.provider = provider;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getAccessTokenEnc() {
        return accessTokenEnc;
    }

    public void setAccessTokenEnc(String accessTokenEnc) {
        this.accessTokenEnc = accessTokenEnc;
    }

    public String getRefreshTokenEnc() {
        return refreshTokenEnc;
    }

    public void setRefreshTokenEnc(String refreshTokenEnc) {
        this.refreshTokenEnc = refreshTokenEnc;
    }

    public Instant getAccessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    public void setAccessTokenExpiresAt(Instant accessTokenExpiresAt) {
        this.accessTokenExpiresAt = accessTokenExpiresAt;
    }

    public String getGrantedScopes() {
        return grantedScopes;
    }

    public void setGrantedScopes(String grantedScopes) {
        this.grantedScopes = grantedScopes;
    }

    public String getSyncCursor() {
        return syncCursor;
    }

    public void setSyncCursor(String syncCursor) {
        this.syncCursor = syncCursor;
    }

    public LocalDate getBackfillThrough() {
        return backfillThrough;
    }

    public void setBackfillThrough(LocalDate backfillThrough) {
        this.backfillThrough = backfillThrough;
    }

    public boolean isBackfillComplete() {
        return backfillComplete;
    }

    public void setBackfillComplete(boolean backfillComplete) {
        this.backfillComplete = backfillComplete;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
