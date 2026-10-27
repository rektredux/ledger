package com.ledger.parse;

import com.ledger.ingest.RawObservation;
import com.ledger.money.Money;
import com.ledger.txn.TransactionStatus;
import com.ledger.txn.TransactionType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads order confirmations and receipts sent by merchants themselves.
 *
 * <p>These are worth parsing separately from bank alerts for one reason: the
 * merchant is unambiguous. A bank alert for the same purchase might say
 * {@code "UPI/SWIGGYLIMITED/4298..."}; the Swiggy receipt says Swiggy. Running
 * at a lower priority than {@link BankAlertParser} means the better merchant
 * name wins, and the deduplication engine then links the bank alert for the
 * same purchase to the transaction this parser created, rather than creating a
 * second one.
 *
 * <p>Receipts typically lack an instrument hint — the merchant does not know
 * which card the user's wallet actually charged — so transactions from this
 * parser often start unattributed to an account and gain that attribution when
 * the bank alert is linked. That is exactly the multiple-sources-one-event
 * design working as intended.
 */
@Component
public class MerchantReceiptParser implements MessageParser {

    /**
     * Known merchant sender domains mapped to a clean display name.
     *
     * <p>Only domains whose mail genuinely contains an order total belong here.
     * The map is small and explicit on purpose: guessing a merchant from an
     * arbitrary sender domain produces a phantom merchant per newsletter.
     */
    private record MerchantDomain(Pattern domain, String displayName) {
    }

    private static final List<MerchantDomain> MERCHANT_DOMAINS = List.of(
            new MerchantDomain(Pattern.compile("swiggy\\.(?:in|com)", Pattern.CASE_INSENSITIVE), "Swiggy"),
            new MerchantDomain(Pattern.compile("zomato\\.com", Pattern.CASE_INSENSITIVE), "Zomato"),
            new MerchantDomain(Pattern.compile("blinkit\\.com|grofers\\.com", Pattern.CASE_INSENSITIVE), "Blinkit"),
            new MerchantDomain(Pattern.compile("zeptonow\\.com", Pattern.CASE_INSENSITIVE), "Zepto"),
            new MerchantDomain(Pattern.compile("bigbasket\\.com", Pattern.CASE_INSENSITIVE), "BigBasket"),
            new MerchantDomain(Pattern.compile("amazon\\.(?:in|com)", Pattern.CASE_INSENSITIVE), "Amazon"),
            new MerchantDomain(Pattern.compile("flipkart\\.com", Pattern.CASE_INSENSITIVE), "Flipkart"),
            new MerchantDomain(Pattern.compile("myntra\\.com", Pattern.CASE_INSENSITIVE), "Myntra"),
            new MerchantDomain(Pattern.compile("ajio\\.com", Pattern.CASE_INSENSITIVE), "AJIO"),
            new MerchantDomain(Pattern.compile("uber\\.com", Pattern.CASE_INSENSITIVE), "Uber"),
            new MerchantDomain(Pattern.compile("olacabs\\.com", Pattern.CASE_INSENSITIVE), "Ola"),
            new MerchantDomain(Pattern.compile("rapido\\.bike", Pattern.CASE_INSENSITIVE), "Rapido"),
            new MerchantDomain(Pattern.compile("irctc\\.co\\.in", Pattern.CASE_INSENSITIVE), "IRCTC"),
            new MerchantDomain(Pattern.compile("goindigo\\.in", Pattern.CASE_INSENSITIVE), "IndiGo"),
            new MerchantDomain(Pattern.compile("makemytrip\\.com", Pattern.CASE_INSENSITIVE), "MakeMyTrip"),
            new MerchantDomain(Pattern.compile("bookmyshow\\.com", Pattern.CASE_INSENSITIVE), "BookMyShow"),
            new MerchantDomain(Pattern.compile("netflix\\.com", Pattern.CASE_INSENSITIVE), "Netflix"),
            new MerchantDomain(Pattern.compile("spotify\\.com", Pattern.CASE_INSENSITIVE), "Spotify"),
            new MerchantDomain(Pattern.compile("pharmeasy\\.in", Pattern.CASE_INSENSITIVE), "PharmEasy"),
            new MerchantDomain(Pattern.compile("1mg\\.com|tata1mg\\.com", Pattern.CASE_INSENSITIVE), "Tata 1mg"),
            new MerchantDomain(Pattern.compile("cult\\.fit|curefit\\.com", Pattern.CASE_INSENSITIVE), "Cult.fit"),
            new MerchantDomain(Pattern.compile("urbancompany\\.com", Pattern.CASE_INSENSITIVE), "Urban Company"));

    /** Wording that marks a receipt rather than marketing from the same sender. */
    private static final Pattern RECEIPT_SIGNAL = Pattern.compile(
            "\\b(?:order\\s+(?:confirmed|placed|summary|details|total|receipt)"
                    + "|your\\s+(?:order|booking|invoice|receipt|ride)"
                    + "|invoice|receipt|payment\\s+(?:successful|received|confirmation)"
                    + "|thank\\s+you\\s+for\\s+(?:your\\s+)?(?:order|purchase|booking)"
                    + "|booking\\s+(?:confirmed|confirmation)|trip\\s+(?:receipt|summary)"
                    + "|subscription\\s+(?:renewed|payment))\\b",
            Pattern.CASE_INSENSITIVE);

    /** Marketing wording that disqualifies a message from this sender. */
    private static final Pattern MARKETING_SIGNAL = Pattern.compile(
            "\\b(?:off\\s+on\\s+your\\s+next|use\\s+code|coupon|flat\\s+\\d+%|save\\s+up\\s+to"
                    + "|deals?\\s+of\\s+the\\s+day|shop\\s+now|order\\s+now|offer\\s+ends"
                    + "|recommended\\s+for\\s+you|back\\s+in\\s+stock)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * The order total. Ordered so a stated grand total wins over a line item,
     * because a receipt lists several amounts and only one is what was charged.
     */
    private static final List<Pattern> TOTAL_PATTERNS = List.of(
            Pattern.compile("(?:grand\\s+total|order\\s+total|total\\s+amount\\s+paid|amount\\s+paid"
                    + "|total\\s+payable|net\\s+payable|you\\s+paid|paid\\s+via\\s+\\w+\\s*:?)"
                    + "\\s*[:\\-]?\\s*(?:rs|inr|₹)\\.?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:total|bill\\s+total)\\s*[:\\-]?\\s*(?:rs|inr|₹)\\.?\\s*"
                    + "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE));

    /** A refund receipt from the merchant. */
    private static final Pattern REFUND_SIGNAL = Pattern.compile(
            "\\b(?:refund(?:ed)?|cancell?ed\\s+and\\s+refunded|your\\s+refund)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final ZoneId LOCAL_ZONE = ZoneId.systemDefault();

    @Override
    public String id() {
        return "merchant-receipt";
    }

    @Override
    public String displayName() {
        return "Merchant receipts and order confirmations";
    }

    @Override
    public int priority() {
        // Before the bank alert parser, so the merchant's own name is used.
        return 100;
    }

    @Override
    public boolean canParse(RawObservation observation) {
        return findMerchantDomain(observation).isPresent();
    }

    @Override
    public Optional<ParseResult> parse(RawObservation observation) {
        Optional<String> merchantName = findMerchantDomain(observation);
        if (merchantName.isEmpty()) {
            return Optional.empty();
        }

        String text = observation.searchableText();

        // A known merchant sends both receipts and marketing. Require receipt
        // wording and reject marketing wording, otherwise a "flat 50% off"
        // mailshot quoting ₹500 becomes a ₹500 expense.
        if (!RECEIPT_SIGNAL.matcher(text).find()) {
            return Optional.empty();
        }
        if (MARKETING_SIGNAL.matcher(text).find() && !hasExplicitTotal(text)) {
            return Optional.empty();
        }

        Optional<Money> total = findTotal(text);
        if (total.isEmpty()) {
            // Receipt wording but no total: often a dispatch or delivery
            // notification for an order already captured. Not a transaction.
            return Optional.empty();
        }

        LocalDate received = observation.receivedAt() == null
                ? LocalDate.now(LOCAL_ZONE)
                : observation.receivedAt().atZone(LOCAL_ZONE).toLocalDate();
        Optional<LocalDate> stated = TransactionTextExtractor.findDate(text, received.getYear());
        LocalDate effectiveDate = stated.orElse(received);

        TransactionType type = REFUND_SIGNAL.matcher(text).find()
                ? TransactionType.REFUND
                : TransactionType.EXPENSE;

        Optional<String> reference = TransactionTextExtractor.findReference(text);

        return Optional.of(ParseResult.builder()
                .amount(total.get())
                .occurredOn(effectiveDate)
                .occurredAtTime(TransactionTextExtractor.findTime(text).orElse(null))
                .merchantRaw(merchantName.get())
                .type(type)
                .status(TransactionStatus.POSTED)
                // Deliberately no instrument hint: the merchant does not state
                // which card or account was actually charged, and inventing one
                // would misattribute the spend to the wrong account.
                .instrumentHint(null)
                .reference(reference.orElse(null))
                .description(TransactionTextExtractor.describe(type, merchantName.get(), effectiveDate))
                // High confidence in the merchant and amount; the date may be
                // the received date and the account is unknown.
                .confidence(stated.isPresent() ? 0.85 : 0.75)
                .build());
    }

    private Optional<String> findMerchantDomain(RawObservation observation) {
        String domain = observation.senderDomain();
        String sender = observation.sender() == null ? "" : observation.sender().toLowerCase(Locale.ROOT);
        for (MerchantDomain candidate : MERCHANT_DOMAINS) {
            if (candidate.domain().matcher(domain).find() || candidate.domain().matcher(sender).find()) {
                return Optional.of(candidate.displayName());
            }
        }
        return Optional.empty();
    }

    private static boolean hasExplicitTotal(String text) {
        return TOTAL_PATTERNS.get(0).matcher(text).find();
    }

    private static Optional<Money> findTotal(String text) {
        for (Pattern pattern : TOTAL_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                Optional<Money> parsed = TransactionTextExtractor.parseAmount(matcher.group(1));
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        // Fall back to the generic amount finder, which already skips balance
        // and limit contexts.
        return TransactionTextExtractor.findAmount(text);
    }
}
