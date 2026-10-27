import { useState } from 'react';
import { AlertTriangle, TrendingUp } from 'lucide-react';
import { api, BudgetProgress, formatINR } from '../api';
import { useApi } from '../hooks/useApi';
import {
  Card, CardHeader, CardBody, PageLoader, EmptyState, ErrorBanner,
  PageHeader, Badge, SectionTitle, ProgressBar, Amount
} from '../components/Layout';

function currentMonth() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

export default function Budgets() {
  const [month, setMonth] = useState(currentMonth());
  const { data, loading, error } = useApi<BudgetProgress[]>(
    () => api.budgets(month), month
  );

  if (loading) return <PageLoader />;
  if (error) return <ErrorBanner message={error} />;

  const budgets = data ?? [];
  const overall = budgets.find(b => b.isOverall);
  const categories = budgets.filter(b => !b.isOverall);

  return (
    <div className="space-y-6 max-w-3xl mx-auto">
      <PageHeader
        title="Budgets"
        subtitle="Monthly limits against your observed spending. Spending figures are the same as the dashboard — incomplete if not all sources are connected."
        actions={
          <input
            type="month"
            value={month}
            onChange={e => setMonth(e.target.value)}
            className="text-xs px-3 py-1.5 rounded"
            style={{
              background: 'var(--surface-0)',
              border: '1px solid var(--border-default)',
              color: 'var(--text-secondary)',
              borderRadius: '6px',
            }}
          />
        }
      />

      {budgets.length === 0 && (
        <Card>
          <CardBody>
            <EmptyState
              title="No budgets set"
              body="Create an overall monthly budget or category-level limits in Settings."
            />
          </CardBody>
        </Card>
      )}

      {/* Overall budget */}
      {overall && (
        <BudgetCard budget={overall} large />
      )}

      {/* Category budgets */}
      {categories.length > 0 && (
        <Card>
          <CardHeader>
            <SectionTitle>Category budgets</SectionTitle>
          </CardHeader>
          <CardBody className="space-y-5">
            {categories.map(b => <BudgetRow key={b.budgetId} budget={b} />)}
          </CardBody>
        </Card>
      )}
    </div>
  );
}

function BudgetCard({ budget, large = false }: { budget: BudgetProgress; large?: boolean }) {
  const { usedPercent, spentMinor, budgetMinor, remainingMinor, warning, exceeded } = budget;
  const variant = exceeded ? 'error' : warning ? 'warning' : 'default';

  return (
    <Card>
      <CardBody>
        <div className="flex items-center justify-between mb-3">
          <div>
            <p className={`font-semibold ${large ? 'text-base' : 'text-sm'}`}
              style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-tight)' }}>
              {budget.categoryName}
            </p>
            {budget.isMonthOverride && (
              <Badge variant="info">Month override</Badge>
            )}
          </div>
          <div className="text-right">
            {exceeded && (
              <div className="flex items-center gap-1 mb-1">
                <AlertTriangle size={12} style={{ color: 'var(--color-error)' }} />
                <span className="text-xs" style={{ color: 'var(--color-error)' }}>Over budget</span>
              </div>
            )}
            {warning && !exceeded && (
              <div className="flex items-center gap-1 mb-1">
                <AlertTriangle size={12} style={{ color: 'var(--color-warning)' }} />
                <span className="text-xs" style={{ color: 'var(--color-warning)' }}>Approaching limit</span>
              </div>
            )}
          </div>
        </div>

        <ProgressBar value={spentMinor} max={budgetMinor} variant={variant} className="mb-3" />

        <div className="grid grid-cols-3 gap-4">
          <div>
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>Spent</p>
            <p className="text-sm font-semibold amount" style={{ color: exceeded ? 'var(--color-error)' : 'var(--text-primary)' }}>
              {formatINR(spentMinor)}
            </p>
          </div>
          <div>
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>Budget</p>
            <p className="text-sm font-semibold amount" style={{ color: 'var(--text-secondary)' }}>
              {formatINR(budgetMinor)}
            </p>
          </div>
          <div>
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>Remaining</p>
            <p className={`text-sm font-semibold amount`}
              style={{ color: remainingMinor < 0 ? 'var(--color-error)' : 'var(--text-secondary)' }}>
              {remainingMinor < 0 ? '−' : ''}{formatINR(Math.abs(remainingMinor))}
            </p>
          </div>
        </div>
        <p className="text-xs mt-2" style={{ color: 'var(--text-disabled)' }}>{usedPercent}% used</p>
      </CardBody>
    </Card>
  );
}

function BudgetRow({ budget }: { budget: BudgetProgress }) {
  const variant = budget.exceeded ? 'error' : budget.warning ? 'warning' : 'default';
  return (
    <div>
      <div className="flex items-center justify-between mb-1.5">
        <span className="text-sm" style={{ color: 'var(--text-secondary)' }}>{budget.categoryName}</span>
        <div className="flex items-center gap-3">
          <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
            {formatINR(budget.spentMinor)} / {formatINR(budget.budgetMinor)}
          </span>
          <span className="text-xs amount" style={{ color: budget.exceeded ? 'var(--color-error)' : 'var(--text-muted)' }}>
            {budget.usedPercent}%
          </span>
        </div>
      </div>
      <ProgressBar value={budget.spentMinor} max={budget.budgetMinor} variant={variant} />
    </div>
  );
}
