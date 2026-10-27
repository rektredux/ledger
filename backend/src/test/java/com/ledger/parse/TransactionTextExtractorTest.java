package com.ledger.parse;

import com.ledger.money.Money;
import com.ledger.txn.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field extraction against representative message text.
 *
 * <p>These are plain unit tests with no Spring context: extraction is pure
 * string handling and deserves to be tested at that speed. The samples are
 * synthetic but modelled closely on real Indian bank, card and UPI alert
 * wording, including the cases that are easy to get wrong — Indian digit
 * grouping, day-first dates, balance amounts sitting next to transaction
 * amounts, and "refund credited" meaning refund rather than income.
 */
class TransactionTextExtractorTest {

    @Nested
    @DisplayName("amount")
    class Amounts {

        @ParameterizedTest
        @CsvSource({
                "'Rs. 1,234.56 debited',               123456",
                "'INR 1234 debited',                   123400",
                "'₹1,234.56 spent',                    123456",
                "'Rs 450.50 paid',                     45050",
                "'amount of Rs.99 debited',            9900",
                "'1,234.56 INR was debited',           123456",
                "'Rs. 1,23,456.78 transferred',        12345678",
                "'Rs 10,00,000 credited',              100000000",
        })
        @DisplayName("parses rupee amounts in Indian digit grouping")
        void parsesAmounts(String text, long expectedMinor) {
            assertThat(TransactionTextExtractor.findAmount(text))
                    .get()
                    .isEqualTo(Money.ofMinor(expectedMinor, "INR"));
        }

        @Test
        @DisplayName("prefers the transaction amount over the available balance")
        void ignoresBalance() {
            // The balance appears first in the string, which is exactly how
            // these alerts are usually laid out and why naive parsing of the
            // "first rupee amount" reports the wrong number.
            String text = "Available balance Rs. 45,000.00. Rs. 250.00 has been debited "
                    + "from your A/c XX1234 on 15-03-2026.";
            assertThat(TransactionTextExtractor.findAmount(text))
                    .get()
                    .isEqualTo(Money.ofMinor(25000L, "INR"));
        }

        @Test
        @DisplayName("ignores credit limit and minimum due amounts")
        void ignoresLimitsAndDues() {
            String text = "Total amount due Rs. 12,500.00, minimum amount due Rs. 625.00. "
                    + "Credit limit Rs. 2,00,000.";
            // Every amount here is a balance-like figure, so reporting none is
            // correct: this message is not a transaction.
            assertThat(TransactionTextExtractor.findAmount(text)).isEmpty();
        }

        @Test
        @DisplayName("rejects a zero amount")
        void rejectsZero() {
            assertThat(TransactionTextExtractor.findAmount("Rs. 0.00 debited")).isEmpty();
        }
    }

    @Nested
    @DisplayName("date")
    class Dates {

        @ParameterizedTest
        @CsvSource({
                "'on 15-03-2026',        2026-03-15",
                "'on 15/03/2026',        2026-03-15",
                "'on 15.03.2026',        2026-03-15",
                "'on 15-03-26',          2026-03-15",
                "'on 15 Mar 2026',       2026-03-15",
                "'on 15-Mar-2026',       2026-03-15",
                "'on 15 March 2026',     2026-03-15",
                "'on Mar 15, 2026',      2026-03-15",
                "'dated 2026-03-15',     2026-03-15",
        })
        @DisplayName("parses the common Indian alert date formats")
        void parsesDates(String text, String expected) {
            assertThat(TransactionTextExtractor.findDate(text, 2026))
                    .get()
                    .isEqualTo(LocalDate.parse(expected));
        }

        @Test
        @DisplayName("reads ambiguous numeric dates day-first, per Indian convention")
        void dayFirstConvention() {
            // 03/04/2026 is 3 April in India, not 4 March. Getting this wrong
            // silently files transactions in the wrong month.
            assertThat(TransactionTextExtractor.findDate("on 03/04/2026", 2026))
                    .get()
                    .isEqualTo(LocalDate.of(2026, 4, 3));
        }

        @Test
        @DisplayName("falls back to month-first when day-first is impossible")
        void swapsWhenDayFirstInvalid() {
            // 12/25 cannot be day 12 of month 25, so it must be month 12 day 25.
            assertThat(TransactionTextExtractor.findDate("on 12/25/2026", 2026))
                    .get()
                    .isEqualTo(LocalDate.of(2026, 12, 25));
        }

        @Test
        @DisplayName("uses the supplied fallback year when the text omits one")
        void usesFallbackYear() {
            assertThat(TransactionTextExtractor.findDate("on 15 Mar", 2024))
                    .get()
                    .isEqualTo(LocalDate.of(2024, 3, 15));
        }

        @Test
        @DisplayName("rejects an invalid calendar date")
        void rejectsInvalidDate() {
            assertThat(TransactionTextExtractor.findDate("on 31-02-2026", 2026)).isEmpty();
        }

        @Test
        @DisplayName("returns empty rather than guessing when no date is present")
        void noDate() {
            assertThat(TransactionTextExtractor.findDate("Rs. 100 debited from your account", 2026))
                    .isEmpty();
        }

        @Test
        @DisplayName("parses a time of day with meridiem")
        void parsesTime() {
            assertThat(TransactionTextExtractor.findTime("at 09:45:30 PM"))
                    .get()
                    .isEqualTo(LocalTime.of(21, 45, 30));
            assertThat(TransactionTextExtractor.findTime("at 12:05 AM"))
                    .get()
                    .isEqualTo(LocalTime.of(0, 5, 0));
        }
    }

    @Nested
    @DisplayName("transaction type")
    class Types {

        @ParameterizedTest
        @CsvSource({
                "'Rs 500 has been debited from your account',              EXPENSE",
                "'Rs 500 was credited to your account',                   INCOME",
                "'You have spent Rs 500 at BigBasket',                    EXPENSE",
                "'Rs 500 withdrawn at ATM',                               EXPENSE",
                "'Your refund of Rs 500 has been credited',               REFUND",
                "'Your transaction has been reversed',                    REVERSAL",
                "'Transaction failed, amount will be credited',           REVERSAL",
                "'Thank you for your payment towards your credit card',   CARD_PAYMENT",
                "'Credit card bill payment of Rs 12500 received',         CARD_PAYMENT",
        })
        @DisplayName("classifies direction from the wording")
        void classifiesTypes(String text, TransactionType expected) {
            assertThat(TransactionTextExtractor.findType(text)).get().isEqualTo(expected);
        }

        @Test
        @DisplayName("a refund that was credited is a refund, not income")
        void refundBeatsCredit() {
            // Both "refund" and "credited" appear. Treating this as income
            // would overstate earnings and leave the original expense standing
            // at full value.
            String text = "Your refund of Rs. 1,299.00 for order OD123456789 has been "
                    + "credited to your account on 20-03-2026.";
            assertThat(TransactionTextExtractor.findType(text))
                    .get()
                    .isEqualTo(TransactionType.REFUND);
        }

        @Test
        @DisplayName("a card bill payment is not an expense")
        void cardPaymentBeatsDebit() {
            // "debited" appears, but this is a liability settlement. Classifying
            // it as an expense would double-count every card purchase.
            String text = "Thank you for your payment towards your credit card ending 4567. "
                    + "Rs. 12,500.00 has been debited from your account XX1234.";
            assertThat(TransactionTextExtractor.findType(text))
                    .get()
                    .isEqualTo(TransactionType.CARD_PAYMENT);
        }

        @Test
        @DisplayName("returns empty when no direction is stated")
        void noDirection() {
            assertThat(TransactionTextExtractor.findType("Your statement is ready")).isEmpty();
        }

        @Test
        @DisplayName("a cash withdrawal is flagged separately from its type")
        void cashWithdrawalIsFlaggedNotTyped() {
            // An ATM withdrawal stays an EXPENSE because that is what the bank
            // reported and because TRANSFER would require naming a destination
            // account the alert does not state. It is kept out of spending
            // totals by its category instead; see CASH_WITHDRAWAL_WORDS.
            String text = "Rs. 5,000.00 withdrawn at ATM from your A/c XX1234 on 15-03-2026";

            assertThat(TransactionTextExtractor.findType(text))
                    .get()
                    .isEqualTo(TransactionType.EXPENSE);
            assertThat(TransactionTextExtractor.isCashWithdrawal(text)).isTrue();
            assertThat(TransactionTextExtractor.isCashWithdrawal(
                    "Rs. 450.00 spent at Swiggy")).isFalse();
        }

        @Test
        @DisplayName("an explicit self-transfer is typed as a transfer")
        void selfTransfer() {
            assertThat(TransactionTextExtractor.findType(
                    "Rs 10,000 transferred to your own account XX9999"))
                    .get()
                    .isEqualTo(TransactionType.TRANSFER);
        }
    }

    @Nested
    @DisplayName("instrument hint")
    class Instruments {

        @ParameterizedTest
        @CsvSource({
                "'from your A/c XX1234',                    1234",
                "'card ending 4567',                        4567",
                "'card ending with 4567',                   4567",
                "'Card no. xxxx4567',                       4567",
                "'account number XXXXXX7890',               7890",
                "'using card **4567',                       4567",
        })
        @DisplayName("extracts only the four-digit tail, never a full number")
        void extractsTail(String text, String expected) {
            assertThat(TransactionTextExtractor.findInstrumentHint(text)).get().isEqualTo(expected);
        }

        @Test
        @DisplayName("extracts a UPI handle but not an email address")
        void extractsVpaNotEmail() {
            assertThat(TransactionTextExtractor.findInstrumentHint("VPA: someone@okhdfcbank"))
                    .get()
                    .isEqualTo("someone@okhdfcbank");
            assertThat(TransactionTextExtractor.findInstrumentHint("contact alerts@hdfcbank.com"))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("reference")
    class References {

        @ParameterizedTest
        @CsvSource({
                "'UPI Ref No 429812345678',                  429812345678",
                "'UPI transaction ID 429812345678',          429812345678",
                "'Transaction Reference No: TXN9876543210',  TXN9876543210",
                "'Ref no: ABCD12345678',                     ABCD12345678",
                "'Order No: OD123456789',                    OD123456789",
        })
        @DisplayName("extracts the provider's event identifier")
        void extractsReference(String text, String expected) {
            assertThat(TransactionTextExtractor.findReference(text)).get().isEqualTo(expected);
        }

        @Test
        @DisplayName("rejects a placeholder of repeated digits")
        void rejectsPlaceholder() {
            assertThat(TransactionTextExtractor.findReference("Ref no: 000000000000")).isEmpty();
        }
    }

    @Nested
    @DisplayName("merchant")
    class Merchants {

        @ParameterizedTest
        @ValueSource(strings = {
                "Rs. 450.00 spent at SWIGGY on 15-03-2026",
                "Rs. 450.00 paid to SWIGGY via UPI",
                "Merchant: SWIGGY",
                "Info: SWIGGY",
        })
        @DisplayName("extracts the merchant from the usual phrasings")
        void extractsMerchant(String text) {
            assertThat(TransactionTextExtractor.findMerchant(text))
                    .get()
                    .asString()
                    .containsIgnoringCase("swiggy");
        }

        @Test
        @DisplayName("does not return a filler word as a merchant")
        void rejectsFillerWords() {
            assertThat(TransactionTextExtractor.findMerchant("credited to your account"))
                    .satisfiesAnyOf(
                            merchant -> assertThat(merchant).isEmpty(),
                            merchant -> assertThat(merchant.get()).doesNotContainIgnoringCase("your"));
        }
    }

    @Nested
    @DisplayName("status")
    class Statuses {

        @Test
        @DisplayName("recognises an authorisation as pending")
        void recognisesPending() {
            assertThat(TransactionTextExtractor.looksPending(
                    "Rs. 5,000.00 has been authorized on your card ending 4567")).isTrue();
            assertThat(TransactionTextExtractor.looksPending(
                    "Rs. 5,000.00 has been debited from your account")).isFalse();
        }
    }
}
