import { api, CreditCard, formatINR } from '../api';
import { useApi } from '../hooks/useApi';
import {
  Card, CardHeader, CardBody, PageLoader, EmptyState, ErrorBanner,
  PageHeader, Badge, SectionTitle, ProgressBar, Amount
} from '../components/Layout';
import { AlertTriangle } from 'lucide-react';

export default function CreditCards() {
  const { data: cards, loading, error } = useApi<CreditCard[]>(() => api.creditCards());

  if (loading) return <PageLoader />;
  if (error) return <ErrorBanner message={error} />;
  if (!cards?.length) {
    return (
      <div className="max-w-4xl mx-auto">
        <PageHeader
          title="Credit Cards"
          subtitle="Statement cycles, outstanding balances and per-cycle spending."
        />
        <Card>
          <CardBody>
            <EmptyState
              title="No credit cards configured"
              body="Add a credit card in Accounts and configure its statement day and due date to track your cycle spending."
            />
          </CardBody>
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      <PageHeader
        title="Credit Cards"
        subtitle="Balances are derived from ingested transactions. Outstanding = purchases minus payments and refunds."
      />

      <div className="space-y-4">
        {cards.map(card => (
          <CardDetail key={card.accountId} card={card} />
        ))}
      </div>

      <p className="text-xs text-center" style={{ color: 'var(--text-disabled)' }}>
        A credit card purchase is an expense. The bill payment is a liability settlement — not a second expense.
        A refund reduces the outstanding balance.
      </p>
    </div>
  );
}

function CardDetail({ card }: { card: CreditCard }) {
  const usedPct = card.utilisationPercent ?? 0;
  const dueSoon = card.currentCycleDueOn &&
    new Date(card.currentCycleDueOn) < new Date(Date.now() + 7 * 24 * 3600 * 1000);

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm font-semibold" style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-tight)' }}>
              {card.name}
            </p>
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
              {card.institution ?? 'Unknown issuer'}
              {card.instrumentHint && ` · ···${card.instrumentHint}`}
            </p>
          </div>
          <div className="text-right">
            <Amount minor={card.outstandingMinor} intent="spending" />
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>outstanding</p>
          </div>
        </div>
      </CardHeader>

      <CardBody>
        {/* Credit utilisation */}
        {card.creditLimitMinor != null ? (
          <div className="mb-5">
            <div className="flex justify-between mb-1.5">
              <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                Credit utilisation
              </span>
              <span className="text-xs amount" style={{ color: usedPct >= 90 ? 'var(--color-error)' : 'var(--text-secondary)' }}>
                {usedPct}%
              </span>
            </div>
            <ProgressBar
              value={card.outstandingMinor ?? 0}
              max={card.creditLimitMinor}
              variant={usedPct >= 90 ? 'error' : usedPct >= 75 ? 'warning' : 'default'}
            />
            <div className="flex justify-between mt-1">
              <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                {formatINR(card.outstandingMinor)} used
              </span>
              <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                {card.availableCreditMinor != null ? formatINR(card.availableCreditMinor) : '—'} available
              </span>
            </div>
          </div>
        ) : (
          <p className="text-xs mb-4" style={{ color: 'var(--text-disabled)' }}>
            Credit limit not configured — utilisation unknown
          </p>
        )}

        {/* Statement cycles */}
        {card.cycleConfigured ? (
          <div className="grid grid-cols-2 gap-4">
            <div>
              <p className="text-xs font-medium mb-2" style={{ color: 'var(--text-muted)' }}>
                Current statement cycle
              </p>
              <p className="text-xs" style={{ color: 'var(--text-secondary)' }}>
                {card.currentCycleFrom} – {card.currentCycleTo}
              </p>
              <p className="text-sm font-semibold amount mt-1" style={{ color: 'var(--text-primary)' }}>
                {card.currentCycleSpendingMinor != null ? formatINR(card.currentCycleSpendingMinor) : '—'}
              </p>
              {card.currentCycleDueOn && (
                <div className="flex items-center gap-1 mt-1">
                  {dueSoon && <AlertTriangle size={12} style={{ color: 'var(--color-warning)' }} />}
                  <p className="text-xs" style={{ color: dueSoon ? 'var(--color-warning)' : 'var(--text-muted)' }}>
                    Due {card.currentCycleDueOn}
                  </p>
                </div>
              )}
            </div>
            <div>
              <p className="text-xs font-medium mb-2" style={{ color: 'var(--text-muted)' }}>
                Previous cycle
              </p>
              <p className="text-xs" style={{ color: 'var(--text-secondary)' }}>
                {card.previousCycleFrom} – {card.previousCycleTo}
              </p>
              <p className="text-sm font-semibold amount mt-1" style={{ color: 'var(--text-primary)' }}>
                {card.previousCycleSpendingMinor != null ? formatINR(card.previousCycleSpendingMinor) : '—'}
              </p>
            </div>
          </div>
        ) : (
          <p className="text-xs" style={{ color: 'var(--text-disabled)' }}>
            Statement day not configured — cycle spending unavailable. Add it in Accounts to track per-cycle spending.
          </p>
        )}
      </CardBody>
    </Card>
  );
}
