package com.ledger.ingest;

import java.time.Instant;
import java.util.Map;

/**
 * A provider-neutral message handed to the parsers.
 *
 * <p>This is the boundary between "how we fetched it" and "what it means".
 * Providers know about OAuth, cursors and pagination; parsers know about bank
 * alert wording. Neither knows about the other, which is what lets a new
 * provider (another mail account, an SMS companion, a statement file) reuse
 * every existing parser, and a new parser work for every provider.
 *
 * <h2>{@code content} is not persisted</h2>
 * The body lives in this object for the duration of parsing and is then
 * dropped. What reaches the database is the extracted fields, a SHA-256
 * {@link #content}, and a short redacted excerpt. The application is a ledger,
 * not a copy of the user's mailbox.
 *
 * @param providerRecordId provider-scoped stable id (a Gmail message id, a CSV
 *     row hash). The uniqueness of this value per provider is what makes
 *     repeated synchronization idempotent.
 * @param sender      the sending address, used to decide which parser applies
 * @param subject     the subject line
 * @param receivedAt  when the provider says it arrived
 * @param content     plain-text body. Never written to the database or logs.
 * @param metadata    provider-specific extras (labels, headers) for parsers
 *     that need them. Small and string-valued by design.
 */
public record RawObservation(String providerRecordId,
                             String sender,
                             String subject,
                             Instant receivedAt,
                             String content,
                             Map<String, String> metadata) {

    public RawObservation {
        if (providerRecordId == null || providerRecordId.isBlank()) {
            throw new IllegalArgumentException("providerRecordId is required for idempotent ingestion");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static RawObservation of(String providerRecordId, String sender, String subject,
                                    Instant receivedAt, String content) {
        return new RawObservation(providerRecordId, sender, subject, receivedAt, content, Map.of());
    }

    /** Subject and body together, lower-cased, for pattern matching. */
    public String searchableText() {
        StringBuilder sb = new StringBuilder();
        if (subject != null) {
            sb.append(subject).append('\n');
        }
        if (content != null) {
            sb.append(content);
        }
        return sb.toString();
    }

    /** The sender's domain, lower-cased, or empty when absent. */
    public String senderDomain() {
        if (sender == null) {
            return "";
        }
        int at = sender.lastIndexOf('@');
        if (at < 0 || at == sender.length() - 1) {
            return "";
        }
        return sender.substring(at + 1).toLowerCase().replaceAll("[>\\s]+$", "");
    }
}
