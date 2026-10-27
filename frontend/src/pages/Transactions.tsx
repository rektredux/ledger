import { useState, useCallback } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Search, Plus, SlidersHorizontal, X } from 'lucide-react';
import { api, Transaction, PageDto, formatINR } from '../api';
import { useApi } from '../hooks/useApi';
import {
  Card, Spinner, EmptyState, ErrorBanner, PageHeader, Badge,
  Table, Th, Td, Tr, Amount, Button, Input, Select, RangePicker,
  SectionTitle
} from '../components/Layout';

type FilterState = {
  range: string;
  search: string;
  type: string;
  accountId: string;
  categoryId: string;
  uncategorized: boolean;
};

export default function Transactions() {
  const [searchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState<FilterState>({
    range: searchParams.get('range') ?? 'this-month',
    search: searchParams.get('search') ?? '',
    type: '',
    accountId: searchParams.get('accountId') ?? '',
    categoryId: searchParams.get('categoryId') ?? '',
    uncategorized: searchParams.get('uncategorized') === 'true',
  });
  const [showFilters, setShowFilters] = useState(false);

  const params: Record<string, string | number | boolean | undefined> = {
    range: filters.range, page, size: 50,
    ...(filters.search && { search: filters.search }),
    ...(filters.type && { type: filters.type }),
    ...(filters.accountId && { accountId: filters.accountId }),
    ...(filters.categoryId && { categoryId: filters.categoryId }),
    ...(filters.uncategorized && { uncategorized: true }),
  };
  const depKey = JSON.stringify(params);
  const { data, loading, error } = useApi<PageDto<Transaction>>(
    () => api.transactions(params), depKey
  );

  const clearFilters = () => setFilters({ range: 'this-month', search: '', type: '', accountId: '', categoryId: '', uncategorized: false });
  const hasActiveFilters = filters.search || filters.type || filters.accountId || filters.categoryId || filters.uncategorized;

  return (
    <div className="space-y-5 max-w-7xl mx-auto">
      <PageHeader
        title="Transactions"
        subtitle="Every recorded financial event. Click a row for full detail and source evidence."
        actions={
          <div className="flex items-center gap-2">
            <Link to="/transactions/new">
              <Button variant="primary" size="sm">
                <Plus size={14} /> Manual entry
              </Button>
            </Link>
          </div>
        }
      />

      {/* Search + filter bar */}
      <div className="flex flex-col sm:flex-row gap-2">
        <div className="relative flex-1">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2"
            style={{ color: 'var(--text-muted)' }} />
          <input
            type="text"
            placeholder="Search merchant, description, reference…"
            value={filters.search}
            onChange={e => { setFilters(f => ({ ...f, search: e.target.value })); setPage(0); }}
            className="w-full pl-9 pr-3 py-2 text-sm rounded"
            style={{
              background: 'var(--surface-0)', border: '1px solid var(--border-default)',
              color: 'var(--text-primary)', borderRadius: '6px',
            }}
          />
        </div>
        <RangePicker value={filters.range} onChange={v => { setFilters(f => ({ ...f, range: v })); setPage(0); }} />
        <Button
          variant={showFilters ? 'secondary' : 'ghost'}
          size="sm"
          onClick={() => setShowFilters(!showFilters)}
        >
          <SlidersHorizontal size={14} /> Filters
          {hasActiveFilters && <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--text-secondary)' }} />}
        </Button>
        {hasActiveFilters && (
          <Button variant="ghost" size="sm" onClick={clearFilters}>
            <X size={14} /> Clear
          </Button>
        )}
      </div>

      {/* Expanded filters */}
      {showFilters && (
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 p-4 rounded-lg"
          style={{ background: 'var(--surface-2)', border: '1px solid var(--border-default)' }}>
          <Select
            label="Type"
            value={filters.type}
            onChange={e => { setFilters(f => ({ ...f, type: e.target.value })); setPage(0); }}
          >
            <option value="">All types</option>
            <option value="EXPENSE">Expenses</option>
            <option value="INCOME">Income</option>
            <option value="REFUND">Refunds</option>
            <option value="REVERSAL">Reversals</option>
            <option value="TRANSFER">Transfers</option>
            <option value="CARD_PAYMENT">Card payments</option>
          </Select>
          <div className="flex items-end">
            <label className="flex items-center gap-2 text-xs cursor-pointer" style={{ color: 'var(--text-secondary)' }}>
              <input
                type="checkbox"
                checked={filters.uncategorized}
                onChange={e => { setFilters(f => ({ ...f, uncategorized: e.target.checked })); setPage(0); }}
              />
              Uncategorized only
            </label>
          </div>
        </div>
      )}

      {/* Transaction table */}
      {loading ? <Spinner /> : error ? <ErrorBanner message={error} /> : !data ? null : (
        <>
          <Card>
            {data.items.length === 0 ? (
              <EmptyState title="No transactions match these filters" />
            ) : (
              <>
                {/* Desktop table */}
                <div className="hidden md:block">
                  <Table>
                    <thead>
                      <tr>
                        <Th>Date</Th>
                        <Th>Merchant / Description</Th>
                        <Th>Category</Th>
                        <Th>Account</Th>
                        <Th>Status</Th>
                        <Th right>Amount</Th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.items.map(txn => (
                        <Tr key={txn.id} onClick={() => window.location.href = `/transactions/${txn.id}`}>
                          <Td>
                            <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                              {txn.occurredOn}
                            </span>
                          </Td>
                          <Td>
                            <p className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                              {txn.merchant ?? txn.description ?? txn.typeLabel}
                            </p>
                            {txn.notes && (
                              <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{txn.notes}</p>
                            )}
                          </Td>
                          <Td>
                            {txn.categoryName ? (
                              <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>
                                {txn.categoryName}
                              </span>
                            ) : (
                              <span className="text-xs" style={{ color: 'var(--text-disabled)' }}>—</span>
                            )}
                          </Td>
                          <Td>
                            <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>
                              {txn.accountName ?? <span style={{ color: 'var(--text-disabled)' }}>Unattributed</span>}
                            </span>
                          </Td>
                          <Td>
                            <TxnStatusBadge txn={txn} />
                          </Td>
                          <Td right>
                            <Amount
                              minor={txn.amountMinor}
                              intent={txn.isSpending ? 'spending' : txn.isIncome ? 'income' : 'neutral'}
                            />
                          </Td>
                        </Tr>
                      ))}
                    </tbody>
                  </Table>
                </div>

                {/* Mobile list */}
                <div className="md:hidden divide-y" style={{ borderColor: 'var(--border-default)' }}>
                  {data.items.map(txn => (
                    <Link key={txn.id} to={`/transactions/${txn.id}`}
                      className="flex items-center justify-between px-4 py-3"
                      style={{ display: 'flex' }}
                    >
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-medium truncate" style={{ color: 'var(--text-primary)' }}>
                          {txn.merchant ?? txn.description ?? txn.typeLabel}
                        </p>
                        <div className="flex items-center gap-2 mt-0.5">
                          <span className="text-xs" style={{ color: 'var(--text-muted)' }}>{txn.occurredOn}</span>
                          {txn.categoryName && (
                            <span className="text-xs" style={{ color: 'var(--text-muted)' }}>{txn.categoryName}</span>
                          )}
                        </div>
                      </div>
                      <Amount
                        minor={txn.amountMinor}
                        intent={txn.isSpending ? 'spending' : txn.isIncome ? 'income' : 'neutral'}
                        className="ml-3"
                      />
                    </Link>
                  ))}
                </div>
              </>
            )}
          </Card>

          {/* Pagination */}
          {data.totalPages > 1 && (
            <div className="flex items-center justify-between text-xs" style={{ color: 'var(--text-muted)' }}>
              <span>{data.totalItems.toLocaleString()} transactions</span>
              <div className="flex items-center gap-2">
                <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>
                  ← Prev
                </Button>
                <span>Page {page + 1} of {data.totalPages}</span>
                <Button variant="ghost" size="sm" disabled={page >= data.totalPages - 1} onClick={() => setPage(p => p + 1)}>
                  Next →
                </Button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function TxnStatusBadge({ txn }: { txn: Transaction }) {
  if (txn.status === 'PENDING') return <Badge variant="warning">Pending</Badge>;
  if (txn.categorySource === 'UNCATEGORIZED') return <Badge variant="muted">Uncategorized</Badge>;
  if (txn.reconciliationStatus === 'MATCHED') return <Badge variant="success">Matched</Badge>;
  return null;
}
