package com.ledger.api;

import com.ledger.budget.Budget;
import com.ledger.budget.BudgetService;
import com.ledger.dataio.BackupService;
import com.ledger.dataio.CsvService;
import com.ledger.dataio.ImportBatch;
import com.ledger.money.Money;
import com.ledger.recurring.RecurringExpense;
import com.ledger.recurring.RecurringService;
import com.ledger.report.DateRange;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * CSV and JSON data import/export, backup/restore, budgets, and recurring
 * expense definitions.
 */
@RestController
@RequestMapping("/api/data")
public class DataController {

    private final CsvService csvService;
    private final BackupService backupService;
    private final BudgetService budgetService;
    private final RecurringService recurringService;

    public DataController(CsvService csvService,
                          BackupService backupService,
                          BudgetService budgetService,
                          RecurringService recurringService) {
        this.csvService = csvService;
        this.backupService = backupService;
        this.budgetService = budgetService;
        this.recurringService = recurringService;
    }

    // --- CSV import/export -------------------------------------------------

    /** The downloadable template with inline guidance comments. */
    @GetMapping("/csv/template")
    public void csvTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=ledger-template.csv");
        response.getWriter().write(csvService.template());
    }

    /**
     * Phase 1: validate and preview an upload without writing anything.
     *
     * <p>Returns per-row errors and a count of rows that look like duplicates.
     * Nothing is committed until the caller explicitly confirms.
     */
    @PostMapping("/csv/preview")
    public CsvService.Preview csvPreview(@RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) String defaultAccountId)
            throws IOException {
        return csvService.preview(file.getInputStream(), file.getOriginalFilename(), defaultAccountId);
    }

    /**
     * Phase 2: commit a previewed upload.
     *
     * <p>The body is the list of valid rows from the preview response. Rows
     * the user wants to skip are simply omitted. The batch id ties the commit
     * to its preview so the same data cannot be committed twice.
     */
    @PostMapping("/csv/commit/{batchId}")
    public ImportBatch csvCommit(@PathVariable String batchId,
                                  @RequestBody List<CsvService.ParsedRow> rows) {
        return csvService.commit(batchId, rows);
    }

    /** Exports all transactions in the date range as a CSV. */
    @GetMapping("/csv/export")
    public void csvExport(@RequestParam(required = false) LocalDate from,
                          @RequestParam(required = false) LocalDate to,
                          HttpServletResponse response) throws IOException {
        LocalDate today = LocalDate.now();
        DateRange range = DateRange.of(
                from == null ? today.withDayOfYear(1) : from,
                to == null ? today : to);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=ledger-export.csv");
        response.getOutputStream().write(
                csvService.export(range).getBytes(StandardCharsets.UTF_8));
    }

    // --- Backup/restore ----------------------------------------------------

    @GetMapping("/backup")
    public void backup(HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Content-Disposition", "attachment; filename=ledger-backup.json");
        response.getOutputStream().write(
                backupService.exportJson().getBytes(StandardCharsets.UTF_8));
    }

    /** Inspects a backup file without committing it. */
    @PostMapping("/backup/inspect")
    public BackupService.Backup inspectBackup(@RequestParam("file") MultipartFile file)
            throws IOException {
        return backupService.inspect(file.getInputStream());
    }

    /**
     * Restores from a backup.
     *
     * @param mode {@code MERGE} (default, safe) or {@code REPLACE} (wipes
     *     everything first — requires explicit intent from the caller)
     */
    @PostMapping("/backup/restore")
    public BackupService.RestoreReport restore(@RequestParam("file") MultipartFile file,
                                               @RequestParam(defaultValue = "MERGE") String mode)
            throws IOException {
        BackupService.RestoreMode restoreMode = BackupService.RestoreMode.valueOf(mode.toUpperCase());
        return backupService.restore(file.getInputStream(), restoreMode);
    }

    // --- Budgets -----------------------------------------------------------

    @GetMapping("/budgets")
    public List<BudgetService.BudgetProgress> budgets(
            @RequestParam(required = false) String month) {
        YearMonth yearMonth = month == null
                ? YearMonth.now()
                : YearMonth.parse(month);
        return budgetService.progressFor(yearMonth);
    }

    @GetMapping("/budgets/alerts")
    public List<BudgetService.BudgetProgress> budgetAlerts() {
        return budgetService.alertsFor(LocalDate.now());
    }

    @PostMapping("/budgets")
    @ResponseStatus(HttpStatus.CREATED)
    public Budget createBudget(@Valid @RequestBody ApiDtos.BudgetRequest request) {
        YearMonth period = request.period() == null ? null : YearMonth.parse(request.period());
        return budgetService.upsert(request.categoryId(),
                Money.ofMinor(request.amountMinor(), Money.INR),
                period,
                request.warnAtPercent() == null ? 80 : request.warnAtPercent());
    }

    @DeleteMapping("/budgets/{id}")
    public void deleteBudget(@PathVariable String id) {
        budgetService.delete(id);
    }

    // --- Recurring ---------------------------------------------------------

    @GetMapping("/recurring")
    public List<RecurringExpense> recurring() {
        return recurringService.findAll();
    }

    @GetMapping("/recurring/upcoming")
    public List<RecurringService.UpcomingPayment> upcoming(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        LocalDate today = LocalDate.now();
        return recurringService.upcoming(
                from == null ? today : from,
                to == null ? today.plusDays(30) : to);
    }

    @GetMapping("/recurring/missed")
    public List<RecurringService.UpcomingPayment> missed() {
        return recurringService.missed(LocalDate.now());
    }

    @PostMapping("/recurring")
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringExpense createRecurring(@Valid @RequestBody ApiDtos.RecurringRequest request) {
        return recurringService.create(request.name(),
                Money.ofMinor(request.amountMinor(), Money.INR),
                RecurringExpense.Cadence.valueOf(request.cadence().toUpperCase()),
                request.dayOfPeriod(),
                request.startsOn(),
                request.categoryId(),
                request.accountId(),
                request.merchant(),
                request.monthOfYear());
    }

    @DeleteMapping("/recurring/{id}")
    public void deleteRecurring(@PathVariable String id) {
        recurringService.delete(id);
    }

    @PostMapping("/recurring/occurrences/{id}/skip")
    public RecurringService.UpcomingPayment skipOccurrence(@PathVariable String id) {
        var skipped = recurringService.skip(id);
        return new RecurringService.UpcomingPayment(skipped.getId(),
                skipped.getRecurringExpenseId(), null, 0, null,
                skipped.getDueOn(), skipped.getStatus().name(),
                false, null, null, null);
    }

    @PostMapping("/recurring/generate")
    public Map<String, Integer> generateOccurrences(
            @RequestParam(required = false) LocalDate through) {
        int created = recurringService.generateThrough(
                through == null ? LocalDate.now().plusMonths(3) : through);
        return Map.of("created", created);
    }
}
