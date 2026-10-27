package com.ledger.api;

import com.ledger.money.Money;
import com.ledger.security.TokenCipher;
import com.ledger.source.TransactionSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates exceptions into RFC 7807 problem responses.
 *
 * <p>Two rules shape what goes into a response body:
 *
 * <ul>
 *   <li><b>Say what the user can do about it.</b> Validation and domain errors
 *       carry their own message, because those messages were written to be
 *       read — "TRANSFER requires a counter account naming the other side of
 *       the movement" is actionable in a way that "400 Bad Request" is not.</li>
 *   <li><b>Never leak internals.</b> An unexpected exception returns a generic
 *       message and a correlation id; the detail goes to the log. A stack trace
 *       or a raw SQL error in an HTTP response can expose schema details and,
 *       for this application, fragments of financial data.</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Domain validation failures.
     *
     * <p>The services throw {@link IllegalArgumentException} with a written-out
     * explanation for anything the caller got wrong — an unknown category, a
     * refund larger than its purchase, a transfer with no counter account.
     * These are safe and useful to return verbatim.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    /** Bean Validation failures on request bodies, reported per field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setTitle("Validation failed");
        problem.setProperty("fields", fieldErrors);
        return problem;
    }

    /**
     * Cross-currency arithmetic.
     *
     * <p>Surfaced as a distinct error because the cause is specific and the
     * remedy is not obvious: the application has no exchange rate source, so it
     * refuses to combine currencies rather than inventing a conversion.
     */
    @ExceptionHandler(Money.CurrencyMismatchException.class)
    public ProblemDetail onCurrencyMismatch(Money.CurrencyMismatchException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()
                + ". This application does not convert between currencies, because it has no "
                + "exchange rate source and a guessed rate would make the totals wrong.");
        problem.setTitle("Currencies cannot be combined");
        return problem;
    }

    /** The stored provider credential is no longer usable. */
    @ExceptionHandler(TransactionSourceProvider.ProviderAuthException.class)
    public ProblemDetail onProviderAuth(TransactionSourceProvider.ProviderAuthException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("The data source needs to be reconnected");
        problem.setProperty("action", "reconnect");
        return problem;
    }

    /** A temporary provider failure. The client may retry. */
    @ExceptionHandler(TransactionSourceProvider.ProviderTransientException.class)
    public ProblemDetail onProviderTransient(TransactionSourceProvider.ProviderTransientException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("The data source is temporarily unavailable");
        problem.setProperty("retryable", true);
        return problem;
    }

    /** Token decryption failed; the user must reconnect. */
    @ExceptionHandler(TokenCipher.TokenCipherException.class)
    public ProblemDetail onTokenCipher(TokenCipher.TokenCipherException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Stored credentials could not be read");
        problem.setProperty("action", "reconnect");
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail onUploadTooLarge(MaxUploadSizeExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                "The uploaded file is larger than the configured limit. "
                        + "Split it into smaller files, or raise "
                        + "spring.servlet.multipart.max-file-size.");
        problem.setTitle("File too large");
        return problem;
    }

    /**
     * Anything unanticipated.
     *
     * <p>The response carries a correlation id and nothing else. The real
     * exception is logged with that id so a user can report "error
     * a1b2c3d4" and the log can be searched, without the response itself
     * carrying internals.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        String correlationId = Long.toHexString(System.nanoTime());
        log.error("Unhandled exception [{}]", correlationId, e);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. The details were written to the backend log under "
                        + "reference " + correlationId + ".");
        problem.setTitle("Unexpected error");
        problem.setProperty("correlationId", correlationId);
        return problem;
    }
}
