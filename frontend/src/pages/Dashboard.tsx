import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
  PieChart, Pie, Cell, Line, LineChart, Area, AreaChart,
} from 'recharts';
import { AlertTriangle, ArrowRight, RefreshCw, TrendingUp, TrendingDown } from 'lucide-react';
import { api, formatINR, DashboardResponse } from '../api';
import { useApi } from '../hooks/useApi';
import {
  Card, CardHeader, CardBody, StatTile, Badge, PageLoader, EmptyState, ErrorBanner,
  WarningBanner, PageHeader, RangePicker, CHART_COLORS, CHART_TOOLTIP_STYLE,
  AXIS_STYLE, GRID_STYLE, Table, Th, Td, Tr, Amount, SectionTitle, Divider
} from '../components/Layout';

const NET_SPENDING_SERIES = [
  { dataKey: 'netSpendingMinor', name: 'Net spending', color: CHART_COLORS[0] },
  { dataKey: 'incomeMinor',      name: 'Income',       color: CHART_COLORS[2] },
];

export default function Dashboard() {
  const [range, setRange] = useState('this-month');
  const { data, loading, error, refetch } = useApi<DashboardResponse>(
    () => api.dashboard(range, 12), range
  );

  if (loading) return <PageLoader />;
  if (error) return <ErrorBanner message={error} />;
  if (!data) return null;

  const { totals, byCategory, byMerchant, monthlyTrend, accounts, creditCards,
          recentTransactions, sources, coverage, reviewCounts } = data;

  const openReviews = Object.values(reviewCounts).reduce((a, b) => a + b, 0);
  const criticalWarnings = coverage.warnings.filter((_, i) => i < 2);
  const sourceIssues = sources.filter(s => s.status !== 'CONNECTED' && s.status !== 'NOT_CONFIGURED');

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      {/* Page header */}
      <PageHeader
        title="Dashboard"
        subtitle="Observed totals from connected sources. Completeness depends on source coverage — see warnings below."
        actions={
          <div className="flex items-center gap-2">
            <RangePicker value={range} onChange={setRange} />
            <button
              onClick={refetch}
              className="p-1.5 rounded"
              style={{ border: '1px solid var(--border-default)', color: 'var(--text-muted)' }}
              title="Refresh"
            >
              <RefreshCw size={14} />
            </button>
          </div>
        }
      />

      {/* Coverage warnings */}
      {criticalWarnings.length > 0 && (
        <div className="space-y-2">
          {criticalWarnings.map((w, i) => <WarningBanner key={i} message={w} />)}
        </div>
      )}

      {/* Review queue alert */}
      {openReviews > 0 && (
        <Link
          to="/sources?tab=review"
          className="flex items-center justify-between px-4 py-3 rounded-lg"
          style={{ background: 'var(--surface-2)', border: '1px solid var(--border-default)', borderRadius: '8px' }}
        >
          <div className="flex items-center gap-3">
            <AlertTriangle size={16} style={{ color: 'var(--text-secondary)' }} />
            <div>
              <p className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                {openReviews} item{openReviews !== 1 ? 's' : ''} need review
              </p>
              <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                Ambiguous matches, parse failures, uncategorized
              </p>
            </div>
          </div>
          <ArrowRight size={14} style={{ color: 'var(--text-muted)' }} />
        </Link>
      )}

      {/* KPI row */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <StatTile
          label="Net spending"
          value={formatINR(totals.netSpendingMinor)}
          sub={totals.spendingOffsetsMinor > 0 ? `${formatINR(totals.spendingOffsetsMinor)} refunded` : undefined}
          trend={totals.spendingChangePercent}
          intent="negative"
        />
        <StatTile
          label="Gross spending"
          value={formatINR(totals.grossSpendingMinor)}
        />
        <StatTile
          label="Income"
          value={formatINR(totals.incomeMinor)}
          intent="positive"
        />
        <StatTile
          label="Net cash flow"
          value={formatINR(totals.netCashFlowMinor)}
          intent={totals.netCashFlowMinor >= 0 ? 'positive' : 'negative'}
        />
      </div>

      {/* Main charts */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">

        {/* Monthly trend — larger */}
        <Card className="lg:col-span-3">
          <CardHeader>
            <SectionTitle>Monthly trend</SectionTitle>
          </CardHeader>
          <CardBody className="pt-2">
            {monthlyTrend.length === 0 ? (
              <EmptyState title="No trend data yet" body="Transactions will appear once sources are connected." />
            ) : (
              <ResponsiveContainer width="100%" height={220}>
                <BarChart data={monthlyTrend} barGap={2} barCategoryGap="30%">
                  <CartesianGrid {...GRID_STYLE} vertical={false} />
                  <XAxis dataKey="period" tick={AXIS_STYLE} tickLine={false} axisLine={false} />
                  <YAxis
                    tickFormatter={(v) => `₹${(v / 100000).toFixed(0)}L`}
                    tick={AXIS_STYLE}
                    tickLine={false}
                    axisLine={false}
                    width={48}
                  />
                  <Tooltip
                    contentStyle={CHART_TOOLTIP_STYLE}
                    formatter={(v: number, name: string) => [formatINR(v), name]}
                    cursor={{ fill: 'var(--surface-2)' }}
                  />
                  <Bar dataKey="netSpendingMinor" name="Spending" fill={CHART_COLORS[0]} radius={[2,2,0,0]} />
                  <Bar dataKey="incomeMinor" name="Income" fill={CHART_COLORS[2]} radius={[2,2,0,0]} />
                </BarChart>
              </ResponsiveContainer>
            )}
          </CardBody>
        </Card>

        {/* Category breakdown — pie */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <div className="flex items-center justify-between">
              <SectionTitle>By category</SectionTitle>
              <Link to={`/reports?range=${range}`} className="text-xs" style={{ color: 'var(--text-muted)' }}>
                Full breakdown →
              </Link>
            </div>
          </CardHeader>
          <CardBody className="pt-2">
            {byCategory.length === 0 ? (
              <EmptyState title="No spending data" />
            ) : (
              <>
                <ResponsiveContainer width="100%" height={160}>
                  <PieChart>
                    <Pie
                      data={byCategory.slice(0, 6)}
                      dataKey="totalMinor"
                      nameKey="name"
                      cx="50%" cy="50%"
                      innerRadius={45}
                      outerRadius={70}
                    >
                      {byCategory.slice(0, 6).map((_, i) => (
                        <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip contentStyle={CHART_TOOLTIP_STYLE} formatter={(v: number) => formatINR(v)} />
                  </PieChart>
                </ResponsiveContainer>
                <div className="space-y-1 mt-3">
                  {byCategory.slice(0, 5).map((c, i) => (
                    <div key={i} className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <span className="inline-block w-2 h-2 rounded-full" style={{ background: CHART_COLORS[i % CHART_COLORS.length] }} />
                        <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{c.name}</span>
                      </div>
                      <span className="text-xs amount" style={{ color: 'var(--text-primary)' }}>
                        {c.sharePercent}%
                      </span>
                    </div>
                  ))}
                </div>
              </>
            )}
          </CardBody>
        </Card>
      </div>

      {/* Accounts + Transactions */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">

        {/* Accounts */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <div className="flex items-center justify-between">
              <SectionTitle>Accounts</SectionTitle>
              <Link to="/accounts" className="text-xs" style={{ color: 'var(--text-muted)' }}>All →</Link>
            </div>
          </CardHeader>
          <div>
            {accounts.length === 0 ? (
              <CardBody>
                <EmptyState title="No accounts yet" action={
                  <Link to="/accounts" className="text-xs" style={{ color: 'var(--text-secondary)' }}>Add account →</Link>
                } />
              </CardBody>
            ) : accounts.slice(0, 6).map((acc, i) => (
              <div key={acc.id}>
                {i > 0 && <Divider />}
                <div className="flex items-center justify-between px-5 py-3">
                  <div>
                    <p className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>{acc.name}</p>
                    <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                      {acc.type.replace('_', ' ')}
                      {acc.balanceConfidence === 'MANUAL_ONLY' ? ' · manual only' : ''}
                    </p>
                  </div>
                  <Amount minor={acc.balanceMinor} intent={acc.isLiability ? 'spending' : 'neutral'} />
                </div>
              </div>
            ))}
          </div>
        </Card>

        {/* Recent transactions */}
        <Card className="lg:col-span-3">
          <CardHeader>
            <div className="flex items-center justify-between">
              <SectionTitle>Recent transactions</SectionTitle>
              <Link to="/transactions" className="text-xs" style={{ color: 'var(--text-muted)' }}>All →</Link>
            </div>
          </CardHeader>
          <div>
            {recentTransactions.length === 0 ? (
              <CardBody>
                <EmptyState
                  title="No transactions yet"
                  body="Connect a data source to collect transactions automatically."
                  action={<Link to="/sources" className="text-xs" style={{ color: 'var(--text-secondary)' }}>Connect Gmail →</Link>}
                />
              </CardBody>
            ) : recentTransactions.map((txn, i) => (
              <div key={txn.id}>
                {i > 0 && <Divider />}
                <Link
                  to={`/transactions/${txn.id}`}
                  className="flex items-center justify-between px-5 py-3 transition-colors"
                  style={{ display: 'flex' }}
                  onMouseEnter={e => { (e.currentTarget as HTMLElement).style.background = 'var(--surface-2)'; }}
                  onMouseLeave={e => { (e.currentTarget as HTMLElement).style.background = ''; }}
                >
                  <div className="min-w-0">
                    <p className="text-sm truncate" style={{ color: 'var(--text-primary)', fontWeight: 500 }}>
                      {txn.merchant ?? txn.description ?? txn.typeLabel}
                    </p>
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="text-xs" style={{ color: 'var(--text-muted)' }}>{txn.occurredOn}</span>
                      {txn.categoryName && (
                        <Badge variant="default">{txn.categoryName}</Badge>
                      )}
                      {txn.status === 'PENDING' && <Badge variant="warning">Pending</Badge>}
                    </div>
                  </div>
                  <div className="ml-4 text-right flex-shrink-0">
                    <Amount
                      minor={txn.amountMinor}
                      intent={txn.isSpending ? 'spending' : txn.isIncome ? 'income' : 'neutral'}
                    />
                    <p className="text-xs mt-0.5" style={{ color: 'var(--text-muted)' }}>
                      {txn.accountName ?? 'Unattributed'}
                    </p>
                  </div>
                </Link>
              </div>
            ))}
          </div>
        </Card>
      </div>

      {/* Top merchants */}
      {byMerchant.length > 0 && (
        <Card>
          <CardHeader>
            <SectionTitle>Top merchants</SectionTitle>
          </CardHeader>
          <CardBody>
            <div className="space-y-3">
              {byMerchant.slice(0, 8).map((m, i) => {
                const maxMinor = byMerchant[0].totalMinor;
                const pct = maxMinor > 0 ? (m.totalMinor / maxMinor) * 100 : 0;
                return (
                  <div key={i}>
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{m.merchant}</span>
                      <span className="text-xs amount" style={{ color: 'var(--text-primary)' }}>{formatINR(m.totalMinor)}</span>
                    </div>
                    <div className="h-1 rounded-full" style={{ background: 'var(--surface-3)' }}>
                      <div className="h-1 rounded-full" style={{ width: `${pct}%`, background: 'var(--chart-1)' }} />
                    </div>
                  </div>
                );
              })}
            </div>
          </CardBody>
        </Card>
      )}

      {/* Source health */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <SectionTitle>Data source health</SectionTitle>
            <Link to="/sources" className="text-xs" style={{ color: 'var(--text-muted)' }}>Manage →</Link>
          </div>
        </CardHeader>
        <CardBody>
          {sources.length === 0 ? (
            <div className="text-xs text-center py-4" style={{ color: 'var(--text-muted)' }}>
              No sources connected.{' '}
              <Link to="/sources" style={{ color: 'var(--text-secondary)', textDecoration: 'underline' }}>
                Connect Gmail
              </Link>{' '}to start collecting transactions automatically.
            </div>
          ) : (
            <div className="space-y-3">
              {sources.map(source => (
                <div key={source.provider} className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                      {source.displayName ?? source.provider}
                    </p>
                    <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                      {source.lastSuccessAt
                        ? `Synced ${new Date(source.lastSuccessAt).toLocaleString('en-IN')}`
                        : 'Never synced'}
                      {!source.backfillComplete && source.backfillThrough && (
                        ` · Import in progress (back to ${source.backfillThrough})`
                      )}
                    </p>
                  </div>
                  <SourceBadge status={source.status} />
                </div>
              ))}
              <p className="text-xs pt-2" style={{ color: 'var(--text-disabled)', borderTop: '1px solid var(--border-default)', paddingTop: 8 }}>
                {coverage.automaticSharePercent}% automatic collection this period ·
                Sync requires this device to be on
              </p>
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  );
}

function SourceBadge({ status }: { status: string }) {
  if (status === 'CONNECTED') return <Badge variant="success">Connected</Badge>;
  if (status === 'NEEDS_REAUTH') return <Badge variant="error">Reconnect needed</Badge>;
  if (status === 'ERROR') return <Badge variant="error">Error</Badge>;
  if (status === 'NOT_CONFIGURED') return <Badge variant="muted">Not configured</Badge>;
  return <Badge variant="default">{status.toLowerCase()}</Badge>;
}
