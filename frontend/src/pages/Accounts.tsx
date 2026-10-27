import { useState } from 'react';
import { Plus, Archive } from 'lucide-react';
import { api, Account, formatINR } from '../api';
import { useApi } from '../hooks/useApi';
import {
  Card, CardHeader, CardBody, PageLoader, EmptyState, ErrorBanner,
  PageHeader, Badge, Button, Table, Th, Td, Tr, Amount,
  SectionTitle, ProgressBar, StatTile
} from '../components/Layout';

export default function Accounts() {
  const [showArchived, setShowArchived] = useState(false);
  const { data, loading, error, refetch } = useApi<Account[]>(
    () => api.accounts(showArchived), String(showArchived)
  );

  if (loading) return <PageLoader />;
  if (error) return <ErrorBanner message={error} />;
  if (!data) return null;

  const bank = data.filter(a => a.type === 'BANK' && !a.archived);
  const cards = data.filter(a => a.type === 'CREDIT_CARD' && !a.archived);
  const wallets = data.filter(a => (a.type === 'CASH' || a.type === 'PREPAID_WALLET') && !a.archived);
  const archived = data.filter(a => a.archived);

  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      <PageHeader
        title="Accounts"
        subtitle="Configure your accounts and instruments so transactions can be attributed correctly."
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="ghost" size="sm"
              onClick={() => setShowArchived(!showArchived)}
            >
              {showArchived ? 'Hide archived' : 'Show archived'}
            </Button>
            <Button variant="primary" size="sm">
              <Plus size={14} /> Add account
            </Button>
          </div>
        }
      />

      {data.length === 0 && (
        <Card>
          <CardBody>
            <EmptyState
              title="No accounts yet"
              body="Add your bank accounts, credit cards and wallets. The instrument hint (last 4 digits or UPI handle) lets alerts be attributed to the right account."
            />
          </CardBody>
        </Card>
      )}

      {bank.length > 0 && (
        <AccountSection title="Bank accounts" accounts={bank} />
      )}
      {cards.length > 0 && (
        <AccountSection title="Credit cards" accounts={cards} />
      )}
      {wallets.length > 0 && (
        <AccountSection title="Cash & wallets" accounts={wallets} />
      )}

      {archived.length > 0 && showArchived && (
        <AccountSection title="Archived" accounts={archived} />
      )}

      {data.length > 0 && archived.length === 0 && (
        <p className="text-xs text-center" style={{ color: 'var(--text-disabled)' }}>
          Accounts are archived rather than deleted, so transaction history is preserved.
        </p>
      )}
    </div>
  );
}

function AccountSection({ title, accounts }: { title: string; accounts: Account[] }) {
  return (
    <Card>
      <CardHeader>
        <SectionTitle>{title}</SectionTitle>
      </CardHeader>
      <Table>
        <thead>
          <tr>
            <Th>Name</Th>
            <Th>Institution</Th>
            <Th>Instrument hint</Th>
            <Th>Confidence</Th>
            <Th right>Balance</Th>
          </tr>
        </thead>
        <tbody>
          {accounts.map(account => (
            <Tr key={account.id}>
              <Td>
                <div className="flex items-center gap-2">
                  <p className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                    {account.name}
                  </p>
                  {account.archived && <Badge variant="muted">archived</Badge>}
                  {account.manualOnly && <Badge variant="info">manual only</Badge>}
                </div>
                <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                  {account.type.replace('_', ' ').toLowerCase()}
                  {account.creditLimitMinor && ` · limit ${formatINR(account.creditLimitMinor)}`}
                </p>
              </Td>
              <Td>
                <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>
                  {account.institution ?? '—'}
                </span>
              </Td>
              <Td>
                {account.instrumentHint ? (
                  <span className="text-xs font-mono px-1.5 py-0.5 rounded"
                    style={{ background: 'var(--surface-3)', color: 'var(--text-secondary)' }}>
                    {account.instrumentHint}
                  </span>
                ) : (
                  <span className="text-xs" style={{ color: 'var(--text-disabled)' }}>
                    Not configured
                  </span>
                )}
              </Td>
              <Td>
                <ConfidenceBadge confidence={account.balanceConfidence} />
              </Td>
              <Td right>
                <Amount
                  minor={account.balanceMinor}
                  intent={account.isLiability ? 'spending' : 'neutral'}
                />
              </Td>
            </Tr>
          ))}
        </tbody>
      </Table>
    </Card>
  );
}

function ConfidenceBadge({ confidence }: { confidence: string }) {
  if (confidence === 'DERIVED_FROM_INGESTION') return <Badge variant="success">Derived</Badge>;
  if (confidence === 'MANUAL_ONLY') return <Badge variant="warning">Manual only</Badge>;
  return <Badge variant="muted">Opening only</Badge>;
}
