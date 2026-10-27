package com.ledger.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single application preference.
 *
 * <p>Deliberately a key/value table rather than a one-row settings entity:
 * preferences are added over time, and a new key should not require a schema
 * migration. Secrets never live here — OAuth material belongs to
 * {@code ProviderConnection} where it is encrypted.
 */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    /** Display currency, ISO-4217. */
    public static final String DISPLAY_CURRENCY = "display.currency";
    /** Display locale for number and date formatting. */
    public static final String DISPLAY_LOCALE = "display.locale";
    /** Marker recording which seed migration populated reference data. */
    public static final String SCHEMA_SEEDED = "schema.seeded";

    @Id
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "value")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AppSetting() {
        // for JPA
    }

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
