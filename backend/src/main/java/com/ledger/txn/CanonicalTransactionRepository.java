package com.ledger.txn;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Queries over the canonical ledger.
 *
 * <h2>The spending predicate</h2>
 * Several queries here repeat the same condition, which is the single most
 * important rule in the application:
 *
 * <pre>
 *   t.type = EXPENSE
 *   AND t.status &lt;&gt; VOID
 *   AND (category is null OR category.excludedFromSpending = false)
 * </pre>
 *
 * Both halves are needed. The type check excludes transfers and credit card
 * bill payments, which are movements rather than consumption. The category
 * check catches the case where something genuinely typed as an expense belongs
 * to a non-spending category anyway — a cash withdrawal or a mutual fund
 * purchase. Dropping either half double-counts money.
 *
 * <p>Refunds and reversals are queried separately and subtracted by the
 * reporting service rather than being folded in with a CASE expression, because
 * the dashboard needs to show gross spending and offsets independently.
 */
public interface CanonicalTransactionRepository
        extends JpaRepository<CanonicalTransaction, String>, JpaSpecificationExecutor<CanonicalTransaction> {

    // --- Deduplication support ---------------------------------------------

    /**
     * Strong-identifier lookup: the first and safest deduplication check.
     * A provider-supplied reference uniquely names the financial event.
     */
    Optional<CanonicalTransaction> findByAccountIdAndExternalReference(String accountId, String externalReference);

    /** Same, for records that arrived before an account could be attributed. */
    List<CanonicalTransaction> findByExternalReference(String externalReference);

    /**
     * Candidate window for heuristic matching: same account and amount, within
     * a few days. Intentionally narrow — it produces candidates to score, and
     * scoring, not this query, decides whether anything is a duplicate.
     */
    @Query("""
            select t from CanonicalTransaction t
            where t.amountMinor = :amountMinor
              and t.currency = :currency
              and t.occurredOn between :from and :to
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and (:accountId is null or t.accountId is null or t.accountId = :accountId)
            order by t.occurredOn asc
            """)
    List<CanonicalTransaction> findMatchCandidates(@Param("amountMinor") long amountMinor,
                                                   @Param("currency") String currency,
                                                   @Param("from") LocalDate from,
                                                   @Param("to") LocalDate to,
                                                   @Param("accountId") String accountId);

    /**
     * Pending transactions on an account that a later posted record might
     * settle. Amount is not constrained: an authorisation often posts for a
     * different amount (tips, fuel holds, FX).
     */
    @Query("""
            select t from CanonicalTransaction t
            where t.status = com.ledger.txn.TransactionStatus.PENDING
              and t.occurredOn between :from and :to
              and (:accountId is null or t.accountId = :accountId)
            order by t.occurredOn asc
            """)
    List<CanonicalTransaction> findPendingForSettlement(@Param("accountId") String accountId,
                                                        @Param("from") LocalDate from,
                                                        @Param("to") LocalDate to);

    /** Candidate original purchases a refund might belong to. */
    @Query("""
            select t from CanonicalTransaction t
            where t.type = com.ledger.txn.TransactionType.EXPENSE
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and t.occurredOn between :from and :to
              and (:accountId is null or t.accountId = :accountId)
              and (:merchant is null or lower(t.merchant) = lower(:merchant))
            order by t.occurredOn desc
            """)
    List<CanonicalTransaction> findRefundCandidates(@Param("accountId") String accountId,
                                                    @Param("merchant") String merchant,
                                                    @Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);

    // --- Listing ------------------------------------------------------------

    Page<CanonicalTransaction> findByOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(
            LocalDate from, LocalDate to, Pageable pageable);

    List<CanonicalTransaction> findTop10ByOrderByOccurredOnDescCreatedAtDesc();

    List<CanonicalTransaction> findByRefundOfId(String refundOfId);

    List<CanonicalTransaction> findByReversesId(String reversesId);

    // --- Aggregates ---------------------------------------------------------

    /**
     * Gross spending in the period, in minor units.
     *
     * <p>{@code coalesce} so an empty period returns 0 rather than null: an
     * empty dataset is a legitimate state, not an error.
     */
    @Query("""
            select coalesce(sum(t.amountMinor), 0)
            from CanonicalTransaction t
            left join Category c on c.id = t.categoryId
            where t.type = com.ledger.txn.TransactionType.EXPENSE
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and (c is null or c.excludedFromSpending = false)
              and t.occurredOn between :from and :to
            """)
    long sumSpendingMinor(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Refunds and reversals in the period, which offset spending.
     * Returned separately so the dashboard can show gross and net.
     */
    @Query("""
            select coalesce(sum(t.amountMinor), 0)
            from CanonicalTransaction t
            left join Category c on c.id = t.categoryId
            where t.type in (com.ledger.txn.TransactionType.REFUND, com.ledger.txn.TransactionType.REVERSAL)
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and (c is null or c.excludedFromSpending = false)
              and t.occurredOn between :from and :to
            """)
    long sumSpendingOffsetsMinor(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Income in the period. Refunds are not income and are excluded. */
    @Query("""
            select coalesce(sum(t.amountMinor), 0)
            from CanonicalTransaction t
            where t.type = com.ledger.txn.TransactionType.INCOME
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and t.occurredOn between :from and :to
            """)
    long sumIncomeMinor(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Spending grouped by category, largest first. */
    @Query("""
            select t.categoryId as categoryId, sum(t.amountMinor) as totalMinor, count(t) as txnCount
            from CanonicalTransaction t
            left join Category c on c.id = t.categoryId
            where t.type = com.ledger.txn.TransactionType.EXPENSE
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and (c is null or c.excludedFromSpending = false)
              and t.occurredOn between :from and :to
            group by t.categoryId
            order by sum(t.amountMinor) desc
            """)
    List<CategoryTotal> sumSpendingByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Spending grouped by normalized merchant, largest first. */
    @Query("""
            select coalesce(t.merchant, 'Unknown') as merchant,
                   sum(t.amountMinor) as totalMinor,
                   count(t) as txnCount
            from CanonicalTransaction t
            left join Category c on c.id = t.categoryId
            where t.type = com.ledger.txn.TransactionType.EXPENSE
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and (c is null or c.excludedFromSpending = false)
              and t.occurredOn between :from and :to
            group by coalesce(t.merchant, 'Unknown')
            order by sum(t.amountMinor) desc
            """)
    List<MerchantTotal> sumSpendingByMerchant(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Spending grouped by the account it was paid from, for the payment-method
     * breakdown. A null account means the source never said which instrument
     * was used; the dashboard shows that as "Unattributed" rather than hiding
     * it, because it is a real gap in the data.
     */
    @Query("""
            select t.accountId as accountId, sum(t.amountMinor) as totalMinor, count(t) as txnCount
            from CanonicalTransaction t
            left join Category c on c.id = t.categoryId
            where t.type = com.ledger.txn.TransactionType.EXPENSE
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and (c is null or c.excludedFromSpending = false)
              and t.occurredOn between :from and :to
            group by t.accountId
            order by sum(t.amountMinor) desc
            """)
    List<AccountTotal> sumSpendingByAccount(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Monthly spending and income for trend charts.
     *
     * <p>{@code substring(occurred_on, 1, 7)} yields {@code yyyy-MM} directly
     * from the ISO-8601 text column, which is why that storage format was
     * chosen. On PostgreSQL this becomes {@code date_trunc('month', ...)}.
     */
    @Query(value = """
            select substr(t.occurred_on, 1, 7) as period,
                   coalesce(sum(case when t.type = 'EXPENSE'
                                      and (c.excluded_from_spending is null or c.excluded_from_spending = 0)
                                     then t.amount_minor else 0 end), 0) as spendingMinor,
                   coalesce(sum(case when t.type = 'INCOME' then t.amount_minor else 0 end), 0) as incomeMinor,
                   coalesce(sum(case when t.type in ('REFUND','REVERSAL')
                                      and (c.excluded_from_spending is null or c.excluded_from_spending = 0)
                                     then t.amount_minor else 0 end), 0) as offsetsMinor
            from canonical_transaction t
            left join category c on c.id = t.category_id
            where t.status <> 'VOID'
              and t.occurred_on between :from and :to
            group by substr(t.occurred_on, 1, 7)
            order by period asc
            """, nativeQuery = true)
    List<MonthlyTotal> sumMonthlyTotals(@Param("from") String from, @Param("to") String to);

    /**
     * Net movement on an account, signed by transaction type, used for derived
     * balances and credit card outstanding.
     *
     * <p>Counts both sides: a transaction whose {@code accountId} is this
     * account moves money in its type's direction, and one whose
     * {@code counterAccountId} is this account moves it the opposite way. That
     * is what makes a transfer net to zero across the pair, and what makes a
     * card bill payment reduce the card's liability while reducing the bank
     * balance.
     */
    @Query(value = """
            select coalesce(sum(signed), 0) from (
              select case t.type
                       when 'EXPENSE'      then -t.amount_minor
                       when 'INCOME'       then  t.amount_minor
                       when 'REFUND'       then  t.amount_minor
                       when 'REVERSAL'     then  t.amount_minor
                       when 'TRANSFER'     then -t.amount_minor
                       when 'CARD_PAYMENT' then -t.amount_minor
                     end as signed
              from canonical_transaction t
              where t.account_id = :accountId and t.status <> 'VOID'
              union all
              select case t.type
                       when 'TRANSFER'     then  t.amount_minor
                       when 'CARD_PAYMENT' then  t.amount_minor
                       else 0
                     end as signed
              from canonical_transaction t
              where t.counter_account_id = :accountId and t.status <> 'VOID'
            )
            """, nativeQuery = true)
    long netMovementMinor(@Param("accountId") String accountId);

    /**
     * Outstanding liability on a credit card.
     *
     * <p>Purchases increase it; refunds, reversals and bill payments reduce it.
     * A bill payment reaches the card as {@code counter_account_id}, which is
     * why it appears in the second branch.
     */
    @Query(value = """
            select coalesce(sum(delta), 0) from (
              select case t.type
                       when 'EXPENSE'  then  t.amount_minor
                       when 'REFUND'   then -t.amount_minor
                       when 'REVERSAL' then -t.amount_minor
                       else 0
                     end as delta
              from canonical_transaction t
              where t.account_id = :accountId and t.status <> 'VOID'
              union all
              select case when t.type = 'CARD_PAYMENT' then -t.amount_minor else 0 end as delta
              from canonical_transaction t
              where t.counter_account_id = :accountId and t.status <> 'VOID'
            )
            """, nativeQuery = true)
    long cardOutstandingMinor(@Param("accountId") String accountId);

    /** Card spending within an explicit statement window. */
    @Query("""
            select coalesce(sum(t.amountMinor), 0)
            from CanonicalTransaction t
            where t.accountId = :accountId
              and t.type = com.ledger.txn.TransactionType.EXPENSE
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and t.occurredOn between :from and :to
            """)
    long sumCardSpendingMinor(@Param("accountId") String accountId,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to);

    /** Spending for one category in a period, for budget progress. */
    @Query("""
            select coalesce(sum(t.amountMinor), 0)
            from CanonicalTransaction t
            where t.type = com.ledger.txn.TransactionType.EXPENSE
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and t.occurredOn between :from and :to
              and (t.categoryId = :categoryId
                   or t.categoryId in (select c.id from Category c where c.parentId = :categoryId))
            """)
    long sumSpendingForCategoryTree(@Param("categoryId") String categoryId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    // --- Data-quality counts used for coverage warnings ---------------------

    /** How many transactions came from each origin, for the coverage panel. */
    @Query("""
            select t.origin as origin, count(t) as txnCount
            from CanonicalTransaction t
            where t.occurredOn between :from and :to
              and t.status <> com.ledger.txn.TransactionStatus.VOID
            group by t.origin
            """)
    List<OriginCount> countByOrigin(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select count(t) from CanonicalTransaction t
            where t.occurredOn between :from and :to
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and (t.categoryId is null
                   or t.categorySource = com.ledger.txn.CategorySource.UNCATEGORIZED)
            """)
    long countUncategorized(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select count(t) from CanonicalTransaction t
            where t.occurredOn between :from and :to
              and t.status <> com.ledger.txn.TransactionStatus.VOID
              and t.accountId is null
            """)
    long countUnattributedToAccount(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long countByStatus(TransactionStatus status);

    long countByReconciliationStatus(ReconciliationStatus status);

    /** The earliest transaction date, to show how far the ledger reaches back. */
    @Query("select min(t.occurredOn) from CanonicalTransaction t")
    LocalDate findEarliestDate();

    // --- Projections --------------------------------------------------------

    interface CategoryTotal {
        String getCategoryId();

        long getTotalMinor();

        long getTxnCount();
    }

    interface MerchantTotal {
        String getMerchant();

        long getTotalMinor();

        long getTxnCount();
    }

    interface AccountTotal {
        String getAccountId();

        long getTotalMinor();

        long getTxnCount();
    }

    interface MonthlyTotal {
        /** {@code yyyy-MM}. */
        String getPeriod();

        long getSpendingMinor();

        long getIncomeMinor();

        long getOffsetsMinor();
    }

    interface OriginCount {
        TransactionOrigin getOrigin();

        long getTxnCount();
    }
}
