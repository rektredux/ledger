package com.ledger.support;

import com.ledger.ingest.RawObservation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Synthetic messages modelled on real Indian bank, card, UPI and merchant mail.
 *
 * <p>Entirely fabricated: no real account numbers, card numbers, references or
 * personal data. The wording follows the structure these alerts actually use,
 * including the parts that are easy to misparse — a balance quoted alongside
 * the transaction amount, Indian digit grouping, day-first dates, and "refund
 * credited" phrasing.
 *
 * <p>Each factory takes an explicit message id so a test can deliberately
 * deliver the same message twice (to prove idempotency) or two different
 * messages describing the same purchase (to prove deduplication).
 */
public final class MessageFixtures {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private MessageFixtures() {
    }

    private static Instant at(LocalDate date) {
        return date.atTime(10, 30).atZone(ZONE).toInstant();
    }

    /** A bank debit alert for a card purchase, with a reference. */
    public static RawObservation cardDebitAlert(String messageId, String amount, String merchant,
                                                LocalDate date, String cardTail, String reference) {
        String body = """
                Dear Customer,

                Rs. %s has been debited from your HDFC Bank Credit Card ending %s
                on %s at %s.

                Transaction Reference No: %s
                Available credit limit: Rs. 1,45,000.00

                If you did not authorise this transaction, call us immediately.
                """.formatted(amount, cardTail, formatDate(date), merchant, reference);
        return RawObservation.of(messageId, "alerts@hdfcbank.net",
                "Transaction alert on your HDFC Bank Credit Card", at(date), body);
    }

    /** A bank account debit alert for a UPI payment. */
    public static RawObservation upiDebitAlert(String messageId, String amount, String merchant,
                                               LocalDate date, String accountTail, String reference) {
        String body = """
                Dear Customer,

                Rs. %s has been debited from your A/c XX%s on %s
                to %s via UPI.

                UPI Ref No %s
                Available balance: Rs. 52,340.75

                - HDFC Bank
                """.formatted(amount, accountTail, formatDate(date), merchant, reference);
        return RawObservation.of(messageId, "alerts@hdfcbank.net",
                "UPI transaction alert", at(date), body);
    }

    /** A salary credit. */
    public static RawObservation salaryCredit(String messageId, String amount, LocalDate date,
                                              String accountTail) {
        String body = """
                Dear Customer,

                Rs. %s has been credited to your A/c XX%s on %s.
                Info: NEFT/SALARY/ACME TECHNOLOGIES PVT LTD

                Available balance: Rs. 2,15,670.25
                """.formatted(amount, accountTail, formatDate(date));
        return RawObservation.of(messageId, "alerts@hdfcbank.net",
                "Salary credited to your account", at(date), body);
    }

    /** A merchant's own order receipt, which names the merchant cleanly. */
    public static RawObservation merchantReceipt(String messageId, String amount, LocalDate date,
                                                 String senderDomain, String orderNo) {
        String body = """
                Your order is confirmed!

                Order No: %s
                Order date: %s

                Item total        Rs. %s
                Delivery fee      Rs. 0.00
                Grand Total: Rs. %s

                Thank you for your order.
                """.formatted(orderNo, formatDate(date), amount, amount);
        return RawObservation.of(messageId, "orders@" + senderDomain,
                "Order confirmed", at(date), body);
    }

    /** A refund credited back for a purchase. */
    public static RawObservation refundAlert(String messageId, String amount, String merchant,
                                             LocalDate date, String accountTail, String reference) {
        String body = """
                Dear Customer,

                Your refund of Rs. %s from %s has been credited to your
                A/c XX%s on %s.

                Transaction Reference No: %s
                Available balance: Rs. 53,590.75
                """.formatted(amount, merchant, accountTail, formatDate(date), reference);
        return RawObservation.of(messageId, "alerts@hdfcbank.net",
                "Refund credited to your account", at(date), body);
    }

    /** A failed transaction that was reversed. */
    public static RawObservation reversalAlert(String messageId, String amount, LocalDate date,
                                               String accountTail, String reference) {
        String body = """
                Dear Customer,

                Your transaction of Rs. %s has been reversed and the amount
                credited back to your A/c XX%s on %s.

                Transaction Reference No: %s
                """.formatted(amount, accountTail, formatDate(date), reference);
        return RawObservation.of(messageId, "alerts@hdfcbank.net",
                "Transaction reversed", at(date), body);
    }

    /** A credit card bill payment: a liability settlement, not an expense. */
    public static RawObservation cardBillPayment(String messageId, String amount, LocalDate date,
                                                 String cardTail, String fromAccountTail) {
        String body = """
                Dear Customer,

                Thank you for your payment towards your credit card ending %s.
                Rs. %s has been debited from your A/c XX%s on %s.

                Your card balance will be updated shortly.
                """.formatted(cardTail, amount, fromAccountTail, formatDate(date));
        return RawObservation.of(messageId, "alerts@hdfcbank.net",
                "Payment received for your credit card", at(date), body);
    }

    /** An authorisation hold that has not yet posted. */
    public static RawObservation pendingAuthorisation(String messageId, String amount, String merchant,
                                                      LocalDate date, String cardTail) {
        String body = """
                Dear Customer,

                Rs. %s has been authorized on your Credit Card ending %s
                at %s on %s. This is a temporary hold and is pending settlement.
                """.formatted(amount, cardTail, merchant, formatDate(date));
        return RawObservation.of(messageId, "alerts@hdfcbank.net",
                "Authorization on your card", at(date), body);
    }

    /** An ATM cash withdrawal. */
    public static RawObservation atmWithdrawal(String messageId, String amount, LocalDate date,
                                               String accountTail) {
        String body = """
                Dear Customer,

                Rs. %s withdrawn at ATM from your A/c XX%s on %s.
                Available balance: Rs. 47,340.75
                """.formatted(amount, accountTail, formatDate(date));
        return RawObservation.of(messageId, "alerts@hdfcbank.net",
                "ATM withdrawal alert", at(date), body);
    }

    // --- Non-transactional mail that must not become transactions ----------

    /** A statement availability notice, which quotes a total due. */
    public static RawObservation statementNotice(String messageId, LocalDate date, String cardTail) {
        String body = """
                Dear Customer,

                Your credit card statement for the month is now available.

                Card ending: %s
                Total amount due: Rs. 24,567.00
                Minimum amount due: Rs. 1,250.00
                Payment due date: %s

                Please pay by the due date to avoid late payment charges.
                """.formatted(cardTail, formatDate(date.plusDays(18)));
        return RawObservation.of(messageId, "statements@hdfcbank.net",
                "Your credit card statement is ready", at(date), body);
    }

    /** An OTP message. */
    public static RawObservation otpMessage(String messageId, LocalDate date) {
        String body = """
                Dear Customer,

                OTP for your transaction of Rs. 4,500.00 is 483920.
                Do not share this OTP with anyone. Valid for 10 minutes.
                """;
        return RawObservation.of(messageId, "alerts@hdfcbank.net",
                "OTP for your transaction", at(date), body);
    }

    /** A promotional offer that quotes amounts. */
    public static RawObservation promotionalOffer(String messageId, LocalDate date) {
        String body = """
                Exclusive offer just for you!

                You are eligible for a pre-approved personal loan of Rs. 5,00,000
                at attractive interest rates. Apply now and get instant approval.

                Flat 50% off on processing fees. Use code SAVE50.
                """;
        return RawObservation.of(messageId, "offers@hdfcbank.net",
                "You are eligible for a pre-approved loan", at(date), body);
    }

    /** A marketing mail from a merchant whose receipts we do parse. */
    public static RawObservation merchantMarketing(String messageId, LocalDate date, String senderDomain) {
        String body = """
                Hungry? Get flat 60% off on your next order!

                Use code HUNGRY60 and save up to Rs. 150 on orders above Rs. 299.
                Offer ends tonight. Order now!
                """;
        return RawObservation.of(messageId, "promo@" + senderDomain,
                "Flat 60% off tonight only", at(date), body);
    }

    private static String formatDate(LocalDate date) {
        // Day-first, the Indian convention these alerts use.
        return "%02d-%02d-%d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }
}
