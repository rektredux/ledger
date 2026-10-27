package com.ledger.provider.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.ingest.RawObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the Gmail message conversion: MIME tree walking, base64url decoding
 * and HTML-to-text reduction.
 *
 * <p>No network and no credentials. These use hand-built Gmail message
 * resources shaped exactly like the API's documented responses, which is the
 * part of the connector that can be verified without a real authorization. The
 * OAuth round trip itself cannot, and the README states that plainly rather
 * than implying a mock proves it.
 *
 * <p>This matters more than it looks: a large share of real bank alerts are
 * HTML-only, so if {@code extractBody} returned nothing for them the parsers
 * would see empty text and every such alert would land in the exception queue.
 */
class GmailApiClientTest {

    private GmailApiClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        client = new GmailApiClient(objectMapper, new GmailProperties());
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("extracts headers, delivery time and a plain-text body")
    void readsSimplePlainTextMessage() {
        String body = "Rs. 450.00 has been debited from your A/c XX1234 on 15-03-2026.";
        JsonNode message = json("""
                {
                  "id": "msg-123",
                  "threadId": "thread-9",
                  "internalDate": "1773561000000",
                  "labelIds": ["INBOX", "CATEGORY_UPDATES"],
                  "payload": {
                    "mimeType": "text/plain",
                    "headers": [
                      {"name": "From", "value": "alerts@hdfcbank.net"},
                      {"name": "Subject", "value": "Transaction alert"},
                      {"name": "Date", "value": "Sun, 15 Mar 2026 10:30:00 +0530"}
                    ],
                    "body": {"data": "%s"}
                  }
                }
                """.formatted(b64(body)));

        RawObservation observation = client.toObservation("msg-123", message);

        assertThat(observation.providerRecordId()).isEqualTo("msg-123");
        assertThat(observation.sender()).isEqualTo("alerts@hdfcbank.net");
        assertThat(observation.subject()).isEqualTo("Transaction alert");
        assertThat(observation.receivedAt()).isEqualTo(Instant.ofEpochMilli(1773561000000L));
        assertThat(observation.content()).contains("Rs. 450.00").contains("XX1234");
        assertThat(observation.metadata()).containsEntry("threadId", "thread-9");
        assertThat(observation.senderDomain()).isEqualTo("hdfcbank.net");
    }

    @Test
    @DisplayName("prefers text/plain over text/html in a multipart/alternative message")
    void prefersPlainTextPart() {
        JsonNode message = json("""
                {
                  "id": "msg-multi",
                  "internalDate": "1773561000000",
                  "payload": {
                    "mimeType": "multipart/alternative",
                    "headers": [{"name": "From", "value": "alerts@icicibank.com"}],
                    "parts": [
                      {"mimeType": "text/plain", "body": {"data": "%s"}},
                      {"mimeType": "text/html",  "body": {"data": "%s"}}
                    ]
                  }
                }
                """.formatted(
                b64("PLAIN: Rs. 100.00 debited"),
                b64("<html><body>HTML: Rs. 999.00 debited</body></html>")));

        RawObservation observation = client.toObservation("msg-multi", message);
        assertThat(observation.content()).contains("PLAIN").doesNotContain("HTML");
    }

    @Test
    @DisplayName("falls back to HTML when there is no plain-text part")
    void fallsBackToHtml() {
        // Many real bank alerts are HTML-only. Without this fallback they would
        // all parse as empty and be reported as coverage gaps.
        JsonNode message = json("""
                {
                  "id": "msg-html",
                  "internalDate": "1773561000000",
                  "payload": {
                    "mimeType": "multipart/alternative",
                    "headers": [{"name": "From", "value": "alerts@axisbank.com"}],
                    "parts": [
                      {"mimeType": "text/html", "body": {"data": "%s"}}
                    ]
                  }
                }
                """.formatted(b64("""
                <html><head><style>.x{color:red}</style></head>
                <body><table><tr><td>Amount</td><td>Rs.&nbsp;1,250.00</td></tr>
                <tr><td>Date</td><td>15-03-2026</td></tr></table>
                <p>has been debited from your A/c XX5678</p></body></html>
                """)));

        RawObservation observation = client.toObservation("msg-html", message);

        assertThat(observation.content())
                .contains("Rs. 1,250.00")
                .contains("15-03-2026")
                .contains("XX5678")
                .as("style and script content must not leak into the text")
                .doesNotContain("color:red");
        assertThat(observation.content()).doesNotContain("<td>").doesNotContain("<p>");
    }

    @Test
    @DisplayName("walks nested multipart trees to find the body")
    void walksNestedParts() {
        JsonNode message = json("""
                {
                  "id": "msg-nested",
                  "internalDate": "1773561000000",
                  "payload": {
                    "mimeType": "multipart/mixed",
                    "headers": [{"name": "From", "value": "alerts@kotak.com"}],
                    "parts": [
                      {"mimeType": "application/pdf", "filename": "statement.pdf",
                       "body": {"attachmentId": "att-1"}},
                      {"mimeType": "multipart/alternative", "parts": [
                        {"mimeType": "text/plain", "body": {"data": "%s"}}
                      ]}
                    ]
                  }
                }
                """.formatted(b64("Rs. 75.50 debited at CAFE")));

        RawObservation observation = client.toObservation("msg-nested", message);
        assertThat(observation.content()).contains("Rs. 75.50").contains("CAFE");
    }

    @Test
    @DisplayName("a message with no readable body yields empty content rather than failing")
    void handlesMissingBody() {
        JsonNode message = json("""
                {
                  "id": "msg-empty",
                  "internalDate": "1773561000000",
                  "payload": {
                    "mimeType": "multipart/mixed",
                    "headers": [{"name": "From", "value": "alerts@sbi.co.in"}],
                    "parts": [
                      {"mimeType": "image/png", "body": {"attachmentId": "att-2"}}
                    ]
                  }
                }
                """);

        RawObservation observation = client.toObservation("msg-empty", message);
        assertThat(observation.content()).isEmpty();
        // The pipeline will report this as a parse failure, which is correct:
        // the message existed and could not be read, so it is a coverage gap.
    }

    @Test
    @DisplayName("undecodable base64 yields empty text instead of throwing")
    void toleratesBadBase64() {
        assertThat(GmailApiClient.decodeBase64Url("!!!not base64!!!")).isEmpty();
    }

    @Test
    @DisplayName("a missing internalDate leaves the received time unknown rather than guessed")
    void missingInternalDate() {
        JsonNode message = json("""
                {
                  "id": "msg-nodate",
                  "payload": {
                    "mimeType": "text/plain",
                    "headers": [{"name": "From", "value": "alerts@hdfcbank.net"}],
                    "body": {"data": "%s"}
                  }
                }
                """.formatted(b64("Rs. 10.00 debited")));

        RawObservation observation = client.toObservation("msg-nodate", message);
        assertThat(observation.receivedAt()).isNull();
    }

    @Test
    @DisplayName("rupee entities decode to the currency symbol")
    void decodesRupeeEntities() {
        assertThat(GmailApiClient.stripHtml("<p>&#8377;1,250.00</p>")).contains("₹1,250.00");
        assertThat(GmailApiClient.stripHtml("<p>&rupee;500</p>")).contains("₹500");
        assertThat(GmailApiClient.stripHtml("<p>Rs.&nbsp;500</p>")).contains("Rs. 500");
    }

    @Test
    @DisplayName("HTML comments and scripts are removed")
    void removesCommentsAndScripts() {
        String html = "<body><!-- hidden Rs. 9999 --><script>var x='Rs. 8888';</script>"
                + "<p>Rs. 100.00 debited</p></body>";
        String text = GmailApiClient.stripHtml(html);

        assertThat(text).contains("Rs. 100.00");
        assertThat(text)
                .as("amounts hidden in comments or scripts must not be parseable")
                .doesNotContain("9999")
                .doesNotContain("8888");
    }
}
