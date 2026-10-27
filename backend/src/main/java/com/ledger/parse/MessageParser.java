package com.ledger.parse;

import com.ledger.ingest.RawObservation;

import java.util.Optional;

/**
 * Extracts transaction fields from one message.
 *
 * <p>Parsers are Spring beans discovered as a list and tried in
 * {@link #priority()} order, so adding support for a new bank means adding one
 * class and nothing else. They are provider-agnostic: a parser sees a
 * {@link RawObservation} and neither knows nor cares whether it arrived from
 * Gmail, a simulated feed or an SMS companion.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #canParse} must be cheap: it is called for every parser on every
 *       message. Check the sender domain and a keyword or two, not the whole
 *       body.</li>
 *   <li>{@link #parse} returns empty when the message turns out not to be a
 *       transaction after all. That is a normal outcome, not a failure, and the
 *       record is marked {@code IGNORED} rather than queued for a human.</li>
 *   <li>{@link #parse} must not throw for malformed input. Return empty or an
 *       incomplete {@link ParseResult}; the pipeline will route an incomplete
 *       result to the exception queue with the reason.</li>
 *   <li>Never invent a field. A null means the message did not say.</li>
 * </ul>
 *
 * <h2>Privacy</h2>
 * Implementations must not log message bodies, subjects, amounts or merchant
 * names. The pipeline persists only extracted fields plus a redacted excerpt.
 */
public interface MessageParser {

    /**
     * Stable identifier recorded on every record this parser handled, e.g.
     * {@code "hdfc-card-alert"}. Used to attribute parse failures to a specific
     * parser and to re-parse a cohort after a fix, so it must not change once
     * records exist.
     */
    String id();

    /** Human-readable name for the Data Sources screen. */
    default String displayName() {
        return id();
    }

    /**
     * Order in which parsers are offered a message; lower runs first.
     *
     * <p>Specific parsers (a named bank's card alert) should sit below generic
     * ones (a best-effort UPI reader) so that the parser with the most context
     * about a format gets first refusal.
     */
    default int priority() {
        return 100;
    }

    /** Cheap pre-check: could this parser handle the message? */
    boolean canParse(RawObservation observation);

    /**
     * Extracts what the message states.
     *
     * @return the extraction, or empty when the message is not a transaction
     */
    Optional<ParseResult> parse(RawObservation observation);
}
