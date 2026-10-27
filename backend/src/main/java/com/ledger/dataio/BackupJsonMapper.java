package com.ledger.dataio;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A dedicated {@link ObjectMapper} for backup files.
 *
 * <p>Separate from the mapper the REST API uses, configured differently on
 * purpose:
 *
 * <ul>
 *   <li><b>Field access, not getters.</b> The entities expose derived
 *       convenience getters — {@code amount()} returns a {@link
 *       com.ledger.money.Money}, {@code effectiveStatus()} computes a default.
 *       Serialising those would put values in the file that have no
 *       corresponding setter, and the restore would then fail trying to read
 *       its own output. Reading and writing private fields instead means the
 *       backup mirrors the database columns exactly.</li>
 *   <li><b>Unknown properties ignored.</b> A backup written by an older version
 *       must still restore after a field is removed. Refusing to read it would
 *       strand the user's only copy of their data.</li>
 *   <li><b>ISO-8601 dates, not epoch numbers.</b> A backup is a file people
 *       keep for years and occasionally open; {@code "2026-03-15"} is
 *       inspectable and {@code 1773561000000} is not.</li>
 * </ul>
 */
@Configuration
public class BackupJsonMapper {

    /**
     * Qualifier-free dedicated bean. Injected by type into
     * {@link BackupService}; the application's primary mapper stays untouched
     * for API serialisation.
     */
    @Bean(name = "backupObjectMapper")
    public ObjectMapper backupObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        // Use fields only. Without this, derived getters become properties with
        // no setter and the restore breaks on its own export.
        mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.ANY));
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY);

        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        return mapper;
    }
}
