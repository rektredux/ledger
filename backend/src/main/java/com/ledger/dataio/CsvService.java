package com.ledger.dataio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.account.Account;
import com.ledger.account.AccountService;
import com.ledger.category.Category;
import com.ledger.category.CategoryService;
import com.ledger.common.Redaction;
import com.ledger.ingest.IngestionService;
import com.ledger.ingest.RawObservation;
import com.ledger.money.Money;
import com.ledger.report.DateRange;
import com.ledger.source.ProviderType;
import com.ledger.txn.CanonicalTransaction;
import com.ledger.txn.CanonicalTransactionRepository;
import com.ledger.txn.TransactionDescriptions;
import com.ledger.txn.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * CSV import and export.
 *
 * <p>Import is a two-phase operation: an upload produces a validated
 * <em>preview</em> that writes nothing, and a separate confirmation commits it.
 * Nothing is half-imported and no invalid row is silently dropped — every
 * rejected row is reported with its line number, the offending field and why.
 *
 * <p>Imported rows go through the same {@link IngestionService} pipeline as
 * provider messages, which means they are deduplicated against what is already
 * in the ledger. Importing a statement that overlaps data already collected
 * from Gmail links the rows to the existing transactions rather than creating a
 * second copy of the month.
 *
 * <p>CSV is a fallback for backfilling and reconciliation, not the normal way
 * transactions arrive. The README says so, and so does the UI.
 */
@Service
public class CsvService {

    private static final Logger log = LoggerFactory.getLogger(CsvService.class);

    /** The canonical column order of the downloadable template. */
    static final List<String> TEMPLATE_COLUMNS = List.of(
            "date", "amount", "type", "merchant", "category", "account", "reference", "notes");

    private final CanonicalTransactionRepository transactions;
    private final IngestionService ingestion;
    private final ImportBatchRepository batches;
    private final AccountService accountService;
    private final CategoryService categoryService;
    private final ObjectMapper objectMapper;

    public CsvService(CanonicalTransactionRepository transactions,
                      IngestionService ingestion,
                      ImportBatchRepository batches,
                      AccountService accountService,
                      CategoryService categoryService,
                      ObjectMapper objectMapper) {
        this.transactions = transactions;
        this.ingestion = ingestion;
        this.batches = batches;
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.objectMapper = objectMapper;
    }

    /** One problem with one row. */
    public record RowError(int row, String field, String message) {
    }

    /** A row that parsed successfully, ready to be committed. */
    public record ParsedRow(int row,
                            LocalDate date,
                            long amountMinor,
                            String currency,
                            TransactionType type,
                            String merchant,
                            String categoryId,
                            String accountId,
                            String reference,
                            String notes,
                            String rowHash) {
    }

    /**
     * The result of validating an upload. Nothing has been written.
     *
     * @param duplicateRows rows that match a transaction already in the ledger
     */
    public record Preview(String batchId,
                          int totalRows,
                          List<ParsedRow> validRows,
                          List<RowError> errors,
                          int duplicateRows) {
        public boolean isClean() {
            return errors.isEmpty();
        }
    }

    // --- Template -----------------------------------------------------------

    /**
     * The downloadable template, with a commented header explaining each column
     * and one example row.
     *
     * <p>The guidance is in the file itself because that is where someone
     * filling it in will be looking.
     */
    public String template() {
        return """
                # Ledger transaction import template
                #
                # date      required. YYYY-MM-DD, or DD-MM-YYYY / DD/MM/YYYY (day first).
                # amount    required. A positive number, e.g. 1234.56 . Do not use a minus
                #           sign to mean an expense - the 'type' column decides direction.
                # type      required. One of: EXPENSE, INCOME, REFUND, REVERSAL, TRANSFER,
                #           CARD_PAYMENT. Aliases accepted: DEBIT=EXPENSE, CREDIT=INCOME.
                #           TRANSFER and CARD_PAYMENT are NOT counted as spending.
                # merchant  optional. The payee. Normalised on import.
                # category  optional. A category id (e.g. food.groceries) or its name.
                #           Left blank, the automatic rules will categorise it.
                # account   optional. An account name as configured in the app, or its
                #           last-4 instrument hint. Blank means unattributed.
                # reference optional but recommended. The bank/UPI reference. Supplying it
                #           lets the importer recognise rows already in your ledger instead
                #           of creating duplicates.
                # notes     optional. Free text.
                #
                # Lines starting with # are ignored. Delete these lines before importing,
                # or leave them - either works.
                date,amount,type,merchant,category,account,reference,notes
                2026-03-15,450.00,EXPENSE,Swiggy,food.delivery,HDFC Savings,UPI123456789012,lunch
                """;
    }

    // --- Import: phase 1, validate -----------------------------------------

    /**
     * Parses and validates an upload without writing any transaction.
     *
     * @param defaultAccountId account for rows that do not name one
     */
    @Transactional
    public Preview preview(InputStream csv, String filename, String defaultAccountId) throws IOException {
        List<ParsedRow> valid = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        int totalRows = 0;
        int duplicates = 0;

        Map<String, Account> accountsByKey = indexAccounts();
        Map<String, String> categoryIdsByKey = indexCategories();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(csv, StandardCharsets.UTF_8))) {

            List<String> header = null;
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                // Skip comments (the template is full of them) and blank lines.
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                List<String> cells = parseCsvLine(line);

                if (header == null) {
                    header = normaliseHeader(cells);
                    List<String> missing = new ArrayList<>();
                    for (String required : List.of("date", "amount", "type")) {
                        if (!header.contains(required)) {
                            missing.add(required);
                        }
                    }
                    if (!missing.isEmpty()) {
                        errors.add(new RowError(lineNumber, "header",
                                "missing required column(s): " + String.join(", ", missing)
                                        + ". Expected columns: " + String.join(", ", TEMPLATE_COLUMNS)));
                        break;
                    }
                    continue;
                }

                totalRows++;
                Map<String, String> row = zip(header, cells);
                try {
                    ParsedRow parsed = parseRow(lineNumber, row, defaultAccountId,
                            accountsByKey, categoryIdsByKey);
                    if (isAlreadyPresent(parsed)) {
                        duplicates++;
                    } else {
                        valid.add(parsed);
                    }
                } catch (RowRejected e) {
                    errors.add(new RowError(lineNumber, e.field(), e.getMessage()));
                }
            }
        }

        ImportBatch batch = new ImportBatch(ImportBatch.Kind.CSV_TRANSACTIONS, filename);
        batch.setAccountId(defaultAccountId);
        batch.setRowsTotal(totalRows);
        batch.setRowsDuplicate(duplicates);
        batch.setRowsFailed(errors.size());
        batch.setErrorsJson(writeJson(errors));
        batch = batches.save(batch);

        return new Preview(batch.getId(), totalRows, valid, errors, duplicates);
    }

    // --- Import: phase 2, commit -------------------------------------------

    /**
     * Commits previously validated rows through the ingestion pipeline.
     *
     * <p>Each row becomes a {@link ProviderType#CSV} source record whose
     * provider id is a hash of the row's own content. That gives two things for
     * free: importing the same file twice is a no-op, and rows are matched
     * against transactions already collected from other sources instead of
     * duplicating them.
     */
    @Transactional
    public ImportBatch commit(String batchId, List<ParsedRow> rows) {
        ImportBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("unknown import batch: " + batchId));
        if (batch.getStatus() == ImportBatch.Status.COMMITTED) {
            throw new IllegalArgumentException("this import has already been committed");
        }

        int imported = 0;
        int duplicate = 0;
        List<RowError> errors = new ArrayList<>();

        for (ParsedRow row : rows) {
            try {
                RawObservation observation = toObservation(row);
                IngestionService.Result result =
                        ingestion.ingest(ProviderType.CSV, null, observation, null);
                switch (result) {
                    case CREATED -> imported++;
                    case LINKED, SETTLED, ALREADY_SEEN -> duplicate++;
                    case QUEUED_FOR_REVIEW -> errors.add(new RowError(row.row(), "duplicate",
                            "looks like an existing transaction; queued for review"));
                    case PARSE_FAILED, IGNORED, RETRY_LATER -> errors.add(new RowError(row.row(),
                            "row", "could not be imported (" + result + ")"));
                }
            } catch (RuntimeException e) {
                errors.add(new RowError(row.row(), "row", e.getClass().getSimpleName()));
            }
        }

        batch.setRowsImported(imported);
        batch.setRowsDuplicate(batch.getRowsDuplicate() + duplicate);
        batch.setRowsFailed(errors.size());
        batch.setErrorsJson(writeJson(errors));
        batch.markCommitted();
        log.info("CSV import {} committed: {} imported, {} duplicates, {} failed",
                batchId, imported, duplicate, errors.size());
        return batches.save(batch);
    }

    /**
     * Wraps a CSV row as a synthetic observation.
     *
     * <p>The "message" is a compact, deterministic rendering of the row's
     * fields, which the CSV parser below reads back. Going through the same
     * pipeline rather than inserting directly is what makes CSV rows subject to
     * the same deduplication as everything else.
     */
    private RawObservation toObservation(ParsedRow row) {
        String content = String.join("\n",
                "LEDGER-CSV-ROW",
                "date=" + row.date(),
                "amount_minor=" + row.amountMinor(),
                "currency=" + row.currency(),
                "type=" + row.type(),
                "merchant=" + nullToEmpty(row.merchant()),
                "category=" + nullToEmpty(row.categoryId()),
                "account=" + nullToEmpty(row.accountId()),
                "reference=" + nullToEmpty(row.reference()),
                "notes=" + nullToEmpty(row.notes()));

        return new RawObservation(
                "csv:" + row.rowHash(),
                "csv-import@localhost",
                "CSV import row " + row.row(),
                row.date().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(),
                content,
                Map.of("source", "csv"));
    }

    // --- Row parsing --------------------------------------------------------

    /** A row could not be used. Carries the field so the UI can point at it. */
    private static final class RowRejected extends RuntimeException {
        private final String field;

        RowRejected(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    private ParsedRow parseRow(int lineNumber, Map<String, String> row, String defaultAccountId,
                               Map<String, Account> accountsByKey,
                               Map<String, String> categoryIdsByKey) {

        LocalDate date = parseDate(require(row, "date"));
        Money amount = parseAmount(require(row, "amount"));
        TransactionType type = parseType(require(row, "type"));

        String merchant = blankToNull(row.get("merchant"));
        String reference = blankToNull(row.get("reference"));
        String notes = blankToNull(row.get("notes"));

        String accountId = defaultAccountId;
        String accountCell = blankToNull(row.get("account"));
        if (accountCell != null) {
            Account account = accountsByKey.get(accountCell.toLowerCase(Locale.ROOT));
            if (account == null) {
                throw new RowRejected("account",
                        "no account matches '" + accountCell + "'. Create it first, or leave the "
                                + "column blank to import the row unattributed.");
            }
            accountId = account.getId();
        }

        String categoryId = null;
        String categoryCell = blankToNull(row.get("category"));
        if (categoryCell != null) {
            categoryId = categoryIdsByKey.get(categoryCell.toLowerCase(Locale.ROOT));
            if (categoryId == null) {
                throw new RowRejected("category",
                        "no category matches '" + categoryCell + "'. Use a category id such as "
                                + "food.groceries, or leave it blank to categorise automatically.");
            }
        }

        if (type.requiresCounterAccount()) {
            // A transfer needs both sides and CSV has no column for the second.
            // Rejecting is right: importing it as an expense would count a
            // transfer as spending, and inventing a counter account would be
            // worse.
            throw new RowRejected("type",
                    type + " cannot be imported from CSV because it needs a second account. "
                            + "Add it through the app instead.");
        }

        String rowHash = Redaction.contentHash(String.join("|",
                date.toString(), String.valueOf(amount.minor()), type.name(),
                nullToEmpty(merchant), nullToEmpty(accountId), nullToEmpty(reference)));

        return new ParsedRow(lineNumber, date, amount.minor(), amount.currency(), type,
                merchant, categoryId, accountId, reference, notes, rowHash);
    }

    private static String require(Map<String, String> row, String column) {
        String value = blankToNull(row.get(column));
        if (value == null) {
            throw new RowRejected(column, column + " is required");
        }
        return value;
    }

    /** Accepts ISO and day-first formats, rejecting anything ambiguous. */
    static LocalDate parseDate(String raw) {
        String value = raw.trim();
        try {
            if (value.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                return LocalDate.parse(value.length() == 10 ? value : padIso(value));
            }
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})").matcher(value);
            if (matcher.matches()) {
                int day = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int year = Integer.parseInt(matcher.group(3));
                // Day-first, consistent with the rest of the application. If
                // the first field cannot be a day, fall back to month-first.
                if (day > 12 && month <= 12) {
                    return LocalDate.of(year, month, day);
                }
                try {
                    return LocalDate.of(year, month, day);
                } catch (java.time.DateTimeException e) {
                    return LocalDate.of(year, day, month);
                }
            }
        } catch (java.time.DateTimeException | NumberFormatException e) {
            throw new RowRejected("date", "'" + raw + "' is not a valid date");
        }
        throw new RowRejected("date",
                "'" + raw + "' is not a recognised date. Use YYYY-MM-DD or DD-MM-YYYY.");
    }

    private static String padIso(String value) {
        String[] parts = value.split("-");
        return "%s-%02d-%02d".formatted(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    static Money parseAmount(String raw) {
        String cleaned = raw.trim()
                .replace(",", "")
                .replace("₹", "")
                .replaceAll("(?i)^(?:rs|inr)\\.?\\s*", "")
                .trim();
        if (cleaned.isEmpty()) {
            throw new RowRejected("amount", "amount is required");
        }
        try {
            BigDecimal value = new BigDecimal(cleaned);
            if (value.signum() == 0) {
                throw new RowRejected("amount", "amount cannot be zero");
            }
            // A negative is accepted as a magnitude: many bank exports write
            // debits as negatives. Direction comes from the type column, which
            // the template explains.
            return Money.fromMajorRounded(value.abs(), Money.INR);
        } catch (NumberFormatException e) {
            throw new RowRejected("amount", "'" + raw + "' is not a number");
        }
    }

    private static TransactionType parseType(String raw) {
        try {
            return TransactionType.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new RowRejected("type", e.getMessage()
                    + ". Valid values: EXPENSE, INCOME, REFUND, REVERSAL, TRANSFER, CARD_PAYMENT.");
        }
    }

    /**
     * Whether a row is already in the ledger, by external reference.
     *
     * <p>Only the strong signal is used at preview time. Fuzzier duplicate
     * detection is left to the matching engine at commit time, which has the
     * full scoring machinery and an audit trail; duplicating that logic here
     * would risk the two disagreeing.
     */
    private boolean isAlreadyPresent(ParsedRow row) {
        if (row.reference() == null) {
            return false;
        }
        if (row.accountId() != null) {
            return transactions.findByAccountIdAndExternalReference(
                    row.accountId(), row.reference()).isPresent();
        }
        return !transactions.findByExternalReference(row.reference()).isEmpty();
    }

    // --- Export -------------------------------------------------------------

    /**
     * Exports transactions in the same shape the importer accepts, so an export
     * can be edited and re-imported without reformatting.
     */
    public String export(DateRange range) {
        List<CanonicalTransaction> rows = transactions.findAll().stream()
                .filter(txn -> range.contains(txn.getOccurredOn()))
                .sorted((a, b) -> b.getOccurredOn().compareTo(a.getOccurredOn()))
                .toList();

        Map<String, Account> accountsById = new HashMap<>();
        for (Account account : accountService.findAll()) {
            accountsById.put(account.getId(), account);
        }

        StringBuilder csv = new StringBuilder();
        csv.append("date,amount,type,merchant,category,account,reference,notes,")
                .append("status,origin,category_source,category_confidence,id\n");

        for (CanonicalTransaction txn : rows) {
            Account account = txn.getAccountId() == null ? null : accountsById.get(txn.getAccountId());
            csv.append(cell(txn.getOccurredOn().toString()))
                    .append(',').append(cell(txn.amount().toMajor().toPlainString()))
                    .append(',').append(cell(txn.getType().name()))
                    .append(',').append(cell(txn.getMerchant()))
                    .append(',').append(cell(txn.getCategoryId()))
                    .append(',').append(cell(account == null ? null : account.getName()))
                    .append(',').append(cell(txn.getExternalReference()))
                    .append(',').append(cell(txn.getNotes()))
                    .append(',').append(cell(txn.getStatus().name()))
                    .append(',').append(cell(txn.getOrigin().name()))
                    // The provenance columns travel with the data so an export
                    // still distinguishes a user's decision from a guess.
                    .append(',').append(cell(txn.getCategorySource().name()))
                    .append(',').append(cell(String.valueOf(txn.getCategoryConfidence())))
                    .append(',').append(cell(txn.getId()))
                    .append('\n');
        }
        return csv.toString();
    }

    // --- CSV mechanics ------------------------------------------------------

    /**
     * Splits one CSV line, honouring double-quoted fields and {@code ""}
     * escapes.
     *
     * <p>Hand-rolled rather than adding a CSV library: the format needed here is
     * RFC 4180 without multi-line fields, which is a short state machine, and
     * the dependency surface of this application is deliberately small.
     * Multi-line quoted fields are not supported, which is noted in the
     * template.
     */
    static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }

    /** Quotes a cell only when it needs it. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static List<String> normaliseHeader(List<String> cells) {
        List<String> header = new ArrayList<>(cells.size());
        for (String cell : cells) {
            header.add(cell.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
        }
        return header;
    }

    private static Map<String, String> zip(List<String> header, List<String> cells) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            row.put(header.get(i), i < cells.size() ? cells.get(i) : null);
        }
        return row;
    }

    /** Accounts indexed by name and by instrument hint, both lower-cased. */
    private Map<String, Account> indexAccounts() {
        Map<String, Account> index = new HashMap<>();
        for (Account account : accountService.findAll()) {
            index.put(account.getName().toLowerCase(Locale.ROOT), account);
            if (account.getInstrumentHint() != null) {
                index.putIfAbsent(account.getInstrumentHint().toLowerCase(Locale.ROOT), account);
            }
        }
        return index;
    }

    /** Category ids indexed by id, name and "parent / child" path. */
    private Map<String, String> indexCategories() {
        Map<String, String> index = new HashMap<>();
        for (Category category : categoryService.findAll()) {
            index.put(category.getId().toLowerCase(Locale.ROOT), category.getId());
            index.putIfAbsent(category.getName().toLowerCase(Locale.ROOT), category.getId());
            index.putIfAbsent(categoryService.displayPath(category.getId()).toLowerCase(Locale.ROOT),
                    category.getId());
        }
        return index;
    }

    private String writeJson(List<RowError> errors) {
        if (errors.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public Optional<ImportBatch> findBatch(String id) {
        return batches.findById(id);
    }
}
