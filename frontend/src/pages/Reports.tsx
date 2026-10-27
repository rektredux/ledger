import { useState } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
  Cell, PieChart, Pie, LineChart, Line, AreaChart, Area,
} from 'recharts';
import { api, formatINR, Totals, CategorySlice, MonthPoint } from '../api';
import { useApi } from '../hooks/useApi';
import {
  Card, CardHeader, CardBody, PageLoader, EmptyState, ErrorBanner,
  PageHeader, RangePicker, SectionTitle, StatTile, CHART_COLORS,
  CHART_TOOLTIP_STYLE, AXIS_STYLE, GRID_STYLE
} from '../components/Layout';

export default function Reports() {
  const [range, setRange] = useState('this-month');

  const { data: totals, loading: tLoading } = useApi<Totals>(
    () => api.totals(range), range
  );
  const { data: categories, loading: cLoading } = useApi<CategorySlice[]>(
    () => api.categories_report ? api.categories_report(range) : Promise.resolve([]),
    range
  );
  const { data: trend, loading: trLoading } = useApi<MonthPoint[]>(
    () => api.trend(12)
  );

  if (tLoading || cLoading || trLoading) return <PageLoader />;

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      <PageHeader
        title="Reports"
        subtitle="Spending analysis over time. All figures from real transactions — not projections."
        actions={<RangePicker value={range} onChange={setRange} />}
      />

      {/* Totals */}
      {totals && (
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
          <StatTile
            label="Net spending"
            value={formatINR(totals.netSpendingMinor)}
            intent="negative"
            trend={totals.spendingChangePercent}
          />
          <StatTile label="Gross spending" value={formatINR(totals.grossSpendingMinor)} />
          <StatTile label="Refunds & reversals" value={formatINR(totals.spendingOffsetsMinor)} intent="positive" />
          <StatTile label="Income" value={formatINR(totals.incomeMinor)} intent="positive" />
        </div>
      )}

      {/* Spending trend (12 months) */}
      {trend && trend.length > 0 && (
        <Card>
          <CardHeader>
            <SectionTitle>12-month spending vs income</SectionTitle>
          </CardHeader>
          <CardBody className="pt-2">
            <ResponsiveContainer width="100%" height={240}>
              <AreaChart data={trend}>
                <defs>
                  <linearGradient id="spendGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor={CHART_COLORS[0]} stopOpacity={0.12} />
                    <stop offset="95%" stopColor={CHART_COLORS[0]} stopOpacity={0} />
                  </linearGradient>
                  <linearGradient id="incGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor={CHART_COLORS[2]} stopOpacity={0.12} />
                    <stop offset="95%" stopColor={CHART_COLORS[2]} stopOpacity={0} />
                  </linearGradient>
                </defs>
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
                />
                <Area
                  type="monotone"
                  dataKey="netSpendingMinor"
                  name="Net spending"
                  stroke={CHART_COLORS[0]}
                  fill="url(#spendGrad)"
                  strokeWidth={1.5}
                />
                <Area
                  type="monotone"
                  dataKey="incomeMinor"
                  name="Income"
                  stroke={CHART_COLORS[2]}
                  fill="url(#incGrad)"
                  strokeWidth={1.5}
                />
              </AreaChart>
            </ResponsiveContainer>
          </CardBody>
        </Card>
      )}

      {/* Category breakdown */}
      {categories && categories.length > 0 && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">

          {/* Horizontal bars — better for comparing named categories */}
          <Card>
            <CardHeader>
              <SectionTitle>Spending by category</SectionTitle>
            </CardHeader>
            <CardBody>
              <div className="space-y-3">
                {categories.slice(0, 10).map((c, i) => {
                  const maxMinor = categories[0].totalMinor;
                  const pct = maxMinor > 0 ? (c.totalMinor / maxMinor) * 100 : 0;
                  return (
                    <div key={i}>
                      <div className="flex justify-between mb-1">
                        <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{c.name}</span>
                        <span className="text-xs amount" style={{ color: 'var(--text-primary)' }}>
                          {formatINR(c.totalMinor)}
                        </span>
                      </div>
                      <div className="h-1.5 rounded-full" style={{ background: 'var(--surface-3)' }}>
                        <div
                          className="h-1.5 rounded-full transition-all duration-500"
                          style={{ width: `${pct}%`, background: CHART_COLORS[i % CHART_COLORS.length] }}
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            </CardBody>
          </Card>

          {/* Donut chart */}
          <Card>
            <CardHeader>
              <SectionTitle>Share of spending</SectionTitle>
            </CardHeader>
            <CardBody className="pt-2">
              <ResponsiveContainer width="100%" height={280}>
                <PieChart>
                  <Pie
                    data={categories.slice(0, 8)}
                    dataKey="totalMinor"
                    nameKey="name"
                    cx="50%" cy="50%"
                    innerRadius={55}
                    outerRadius={90}
                  >
                    {categories.slice(0, 8).map((_, i) => (
                      <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip
                    contentStyle={CHART_TOOLTIP_STYLE}
                    formatter={(v: number, name: string) => [formatINR(v), name]}
                  />
                </PieChart>
              </ResponsiveContainer>
              <div className="grid grid-cols-2 gap-1 mt-2">
                {categories.slice(0, 8).map((c, i) => (
                  <div key={i} className="flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full flex-shrink-0"
                      style={{ background: CHART_COLORS[i % CHART_COLORS.length] }} />
                    <span className="text-xs truncate" style={{ color: 'var(--text-secondary)' }}>
                      {c.name}
                    </span>
                    <span className="text-xs ml-auto amount" style={{ color: 'var(--text-muted)' }}>
                      {c.sharePercent}%
                    </span>
                  </div>
                ))}
              </div>
            </CardBody>
          </Card>
        </div>
      )}

      {(!trend?.length && !categories?.length) && (
        <Card>
          <CardBody>
            <EmptyState title="No data for this period" body="Connect a data source to start collecting transactions." />
          </CardBody>
        </Card>
      )}
    </div>
  );
}
