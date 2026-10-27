package com.ledger.api;

import com.ledger.report.DashboardService;
import com.ledger.report.DateRange;
import com.ledger.review.ReviewItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dashboard and reports.
 *
 * <p>Responses always include the {@code coverage} block. That is not optional
 * padding: it is how the API avoids implying that a spending total is complete.
 * The numbers are exact sums of the ledger; coverage states what the ledger is
 * and is not based on, and the UI renders the two together.
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboard;
    private final TransactionMapper mapper;

    public DashboardController(DashboardService dashboard, TransactionMapper mapper) {
        this.dashboard = dashboard;
        this.mapper = mapper;
    }

    /** The dashboard payload, as the UI consumes it. */
    public record DashboardResponse(LocalDate from,
                                    LocalDate to,
                                    String rangeLabel,
                                    DashboardService.Totals totals,
                                    List<DashboardService.CategorySlice> byCategory,
                                    List<DashboardService.MerchantSlice> byMerchant,
                                    List<DashboardService.PaymentMethodSlice> byPaymentMethod,
                                    List<DashboardService.MonthPoint> monthlyTrend,
                                    List<ApiDtos.AccountDto> accounts,
                                    List<ApiDtos.CreditCardDto> creditCards,
                                    List<ApiDtos.TransactionDto> recentTransactions,
                                    List<DashboardService.SourceHealth> sources,
                                    DashboardService.Coverage coverage,
                                    Map<String, Long> reviewCounts) {
    }

    /**
     * Builds the dashboard for a period.
     *
     * @param range a preset name ({@code this-month}, {@code last-month},
     *     {@code last-7-days}, {@code last-30-days}, {@code last-90-days},
     *     {@code this-year}), ignored when {@code from} and {@code to} are given
     */
    @GetMapping("/dashboard")
    public DashboardResponse dashboard(@RequestParam(required = false) String range,
                                       @RequestParam(required = false) LocalDate from,
                                       @RequestParam(required = false) LocalDate to,
                                       @RequestParam(defaultValue = "12") int trendMonths) {

        LocalDate today = LocalDate.now();
        DateRange period = resolveRange(range, from, to, today);

        DashboardService.Dashboard built =
                dashboard.build(period, today, Math.clamp(trendMonths, 1, 24));

        Map<String, Long> reviewCounts = new LinkedHashMap<>();
        for (Map.Entry<ReviewItem.Kind, Long> entry : dashboard.reviewCounts().entrySet()) {
            reviewCounts.put(entry.getKey().name(), entry.getValue());
        }

        return new DashboardResponse(
                period.from(), period.to(), describe(range, period),
                built.totals(),
                built.byCategory(),
                built.byMerchant(),
                built.byPaymentMethod(),
                built.monthlyTrend(),
                built.accounts().stream().map(ApiDtos::toAccountDto).toList(),
                built.creditCards().stream().map(ApiDtos::toCreditCardDto).toList(),
                mapper.toDtos(built.recentTransactions()),
                built.sources(),
                built.coverage(),
                reviewCounts);
    }

    /** Category breakdown on its own, for the Reports screen. */
    @GetMapping("/reports/categories")
    public List<DashboardService.CategorySlice> categories(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        DateRange period = resolveRange(range, from, to, LocalDate.now());
        return dashboard.categoryBreakdown(period, dashboard.totals(period).grossSpendingMinor());
    }

    @GetMapping("/reports/merchants")
    public List<DashboardService.MerchantSlice> merchants(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "25") int limit) {
        DateRange period = resolveRange(range, from, to, LocalDate.now());
        return dashboard.merchantBreakdown(period, Math.clamp(limit, 1, 200));
    }

    @GetMapping("/reports/payment-methods")
    public List<DashboardService.PaymentMethodSlice> paymentMethods(
            @RequestParam(required = false) String range,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        DateRange period = resolveRange(range, from, to, LocalDate.now());
        return dashboard.paymentMethodBreakdown(period);
    }

    @GetMapping("/reports/trend")
    public List<DashboardService.MonthPoint> trend(@RequestParam(defaultValue = "12") int months) {
        return dashboard.monthlyTrend(
                DateRange.trailingMonths(LocalDate.now(), Math.clamp(months, 1, 36)));
    }

    /** Totals only, for lightweight polling. */
    @GetMapping("/reports/totals")
    public DashboardService.Totals totals(@RequestParam(required = false) String range,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to) {
        return dashboard.totals(resolveRange(range, from, to, LocalDate.now()));
    }

    /** Coverage on its own, for the warnings banner. */
    @GetMapping("/reports/coverage")
    public DashboardService.Coverage coverage(@RequestParam(required = false) String range,
                                              @RequestParam(required = false) LocalDate from,
                                              @RequestParam(required = false) LocalDate to) {
        return dashboard.coverage(resolveRange(range, from, to, LocalDate.now()));
    }

    /**
     * Resolves a preset name or an explicit range.
     *
     * <p>An explicit {@code from}/{@code to} pair always wins, so a custom
     * period cannot be silently overridden by a stale preset parameter.
     */
    static DateRange resolveRange(String range, LocalDate from, LocalDate to, LocalDate today) {
        if (from != null && to != null) {
            return DateRange.of(from, to);
        }
        if (from != null) {
            return DateRange.of(from, today);
        }
        String preset = range == null ? "this-month" : range.trim().toLowerCase();
        return switch (preset) {
            case "last-month" -> DateRange.lastMonth(today);
            case "last-7-days", "last7days" -> DateRange.last7Days(today);
            case "last-30-days", "last30days" -> DateRange.last30Days(today);
            case "last-90-days", "last90days" -> DateRange.last90Days(today);
            case "this-year", "thisyear" -> DateRange.thisYear(today);
            default -> DateRange.thisMonth(today);
        };
    }

    private static String describe(String range, DateRange period) {
        if (range != null && !range.isBlank()) {
            return range;
        }
        return period.isWholeMonth() ? period.asPeriod() : period.toString();
    }
}
