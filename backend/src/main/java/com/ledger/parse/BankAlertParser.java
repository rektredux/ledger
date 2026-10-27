package com.ledger.parse;

import com.ledger.ingest.RawObservation;
import com.ledger.money.Money;
import com.ledger.txn.TransactionStatus;
import com.ledger.txn.TransactionType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads bank, debit card, credit card and UPI transaction alerts.
 *
 * <p>One parser rather than one per bank, because these alerts are far more
 * alike than they are different: a direction verb, an amount, an instrument
 * tail, a date, usually a merchant and often a reference. The variation is in
 * wording, which {@link TransactionTextExtractor} absorbs. A per-bank parser
 * would mean the format of every bank the user does <em>not</em> hold is dead
 * code, and a bank whose template changes slightly stops parsing entirely.
 *
 * <p>Where a specific institution eventually needs special handling, it gets
 * its own parser at a lower {@link #priority()} and this one remains the
 * fallback.
 *
 * <h2>Confidence</h2>
 * The score reflects how much of the message was actually understood, not how
 * likely it is to be a transaction. A record with an amount, date, type,
 * instrument and reference is far more trustworthy — and far easier to
 * deduplicate — than one with only an amount and a type, and the matcher and
 * the UI both need to know which they are looking at.
 */
@Component
public class BankAlertParser implements MessageParser {

    /** Sender-domain fragments for banks and card issuers operating in India. */
    private static final Pattern FINANCIAL_SENDER = Pattern.compile(
            "(?:hdfcbank|icicibank|axisbank|sbi|onlinesbi|kotak|yesbank|idfcfirstbank|indusind"
                    + "|rblbank|federalbank|bankofbaroda|pnb|canarabank|unionbankofindia|aubank"
                    + "|bandhanbank|dbs|citibank|hsbc|standardchartered|amex|americanexpress"
                    + "|sc\\.com|paytm|phonepe|payzapp|freecharge|mobikwik|npci|upi)",
            Pattern.CASE_INSENSITIVE);

    /** Subject/body wording that marks a message as transactional. */
    private static final Pattern TRANSACTION_SIGNAL = Pattern.compile(
            "\\b(?:debited|credited|debit|credit|spent|paid|withdrawn|transaction|txn|purchase"
                    + "|refund|reversed|transferred|received|upi)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final ZoneId LOCAL_ZONE = ZoneId.systemDefault();

    @Override
    public String id() {
        return "bank-alert";
    }

    @Override
    public String displayName() {
        return "Bank, card and UPI alerts";
    }

    @Override
    public int priority() {
        // Runs after merchant receipts: a Swiggy order confirmation carries a
        // better merchant name than a bank alert for the same purchase.
        return 200;
    }

    @Override
    public boolean canParse(RawObservation observation) {
        // Either the sender is a known financial institution, or the wording is
        // unmistakably transactional. The second branch matters because banks
        // send from an endless supply of subdomains and no-reply addresses.
        if (FINANCIAL_SENDER.matcher(observation.senderDomain()).find()) {
            return true;
        }
        String sender = observation.sender() == null ? "" : observation.sender();
        if (FINANCIAL_SENDER.matcher(sender).find()) {
            return true;
        }
        return TRANSACTION_SIGNAL.matcher(observation.searchableText()).find();
    }

    @Override
    public Optional<ParseResult> parse(RawObservation observation) {
        String text = observation.searchableText();
        if (text.isBlank()) {
            return Optional.empty();
        }

        Optional<TransactionType> type = TransactionTextExtractor.findType(text);
        Optional<Money> amount = TransactionTextExtractor.findAmount(text);

        // Without a direction and an amount there is no transaction to speak
        // of. Returning empty marks this "not a transaction" rather than "failed
        // to parse a transaction", which is the honest distinction: most such
        // messages really are not transactions.
        if (type.isEmpty() && amount.isEmpty()) {
            return Optional.empty();
        }

        int fallbackYear = fallbackYear(observation);
        Optional<LocalDate> date = TransactionTextExtractor.findDate(text, fallbackYear);
        Optional<LocalTime> time = TransactionTextExtractor.findTime(text);
        Optional<String> merchant = TransactionTextExtractor.findMerchant(text);
        Optional<String> instrument = TransactionTextExtractor.findInstrumentHint(text);
        Optional<String> reference = TransactionTextExtractor.findReference(text);

        // A bank alert almost always concerns the day it was sent. Using the
        // received date when the body states none is a documented, visible
        // fallback rather than an invention: without it an otherwise complete
        // alert would be discarded, and the received date is correct to within
        // a day in practice.
        LocalDate effectiveDate = date.orElseGet(() -> receivedDate(observation));

        TransactionStatus status = TransactionTextExtractor.looksPending(text)
                ? TransactionStatus.PENDING
                : TransactionStatus.POSTED;

        ParseResult result = ParseResult.builder()
                .amount(amount.orElse(null))
                .occurredOn(amount.isPresent() ? effectiveDate : null)
                .occurredAtTime(time.orElse(null))
                .merchantRaw(merchant.orElse(null))
                .type(type.orElse(null))
                .status(status)
                .instrumentHint(instrument.orElse(null))
                .reference(reference.orElse(null))
                .description(type.map(t -> TransactionTextExtractor
                                .describe(t, merchant.orElse(null), effectiveDate))
                        .orElse(null))
                .confidence(confidence(amount.isPresent(), type.isPresent(), date.isPresent(),
                        instrument.isPresent(), reference.isPresent(), merchant.isPresent()))
                .build();

        return Optional.of(result);
    }

    /**
     * Confidence as a function of how completely the message was understood.
     *
     * <p>Amount and type are the load-bearing fields and are weighted
     * accordingly. An explicit date beats the received-date fallback. The
     * instrument and reference add confidence because they are what let the
     * matcher attribute and deduplicate the transaction with certainty rather
     * than by scoring.
     */
    private static double confidence(boolean hasAmount, boolean hasType, boolean hasExplicitDate,
                                     boolean hasInstrument, boolean hasReference, boolean hasMerchant) {
        double score = 0.0;
        score += hasAmount ? 0.35 : 0.0;
        score += hasType ? 0.25 : 0.0;
        score += hasExplicitDate ? 0.15 : 0.05;
        score += hasInstrument ? 0.10 : 0.0;
        score += hasReference ? 0.10 : 0.0;
        score += hasMerchant ? 0.05 : 0.0;
        return Math.clamp(score, 0.0, 1.0);
    }

    private static int fallbackYear(RawObservation observation) {
        return receivedDate(observation).getYear();
    }

    private static LocalDate receivedDate(RawObservation observation) {
        return observation.receivedAt() == null
                ? LocalDate.now(LOCAL_ZONE)
                : observation.receivedAt().atZone(LOCAL_ZONE).toLocalDate();
    }
}
