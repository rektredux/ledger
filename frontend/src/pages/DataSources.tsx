import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  CheckCircle2, AlertTriangle, XCircle, ExternalLink, RefreshCw, Trash2
} from 'lucide-react';
import { api, SourceHealth, formatINR } from '../api';
import { useApi } from '../hooks/useApi';
import {
  Card, CardHeader, CardBody, PageLoader, EmptyState, ErrorBanner,
  PageHeader, Badge, Button, SectionTitle, Spinner, InfoBanner, WarningBanner,
  Table, Th, Td, Tr
} from '../components/Layout';

type Tab = 'sources' | 'review';

export default function DataSources() {
  const [searchParams] = useSearchParams();
  const [tab, setTab] = useState<Tab>(
    (searchParams.get('tab') as Tab) ?? 'sources'
  );
  const [syncing, setSyncing] = useState(false);
  const [syncResult, setSyncResult] = useState<string | null>(null);

  const { data: sources, loading, error, refetch } = useApi<SourceHealth[]>(() => api.sourceStatus());
  const { data: reviewData } = useApi(() => api.reviewQueue(0));
  const { data: reviewCounts } = useApi(() => api.reviewCounts());

  const openReviews = reviewCounts ? Object.values(reviewCounts).reduce((a, b) => a + b, 0) : 0;

  const handleManualSync = async (provider: string) => {
    setSyncing(true);
    setSyncResult(null);
    try {
      const result = await api.manualSync(provider);
      setSyncResult(result.triggered ? `Sync triggered for ${provider}` : `Could not trigger: ${result.reason}`);
      setTimeout(refetch, 3000);
    } catch (e) {
      setSyncResult(String(e));
    }
    setSyncing(false);
  };

  const handleGmailConnect = async () => {
    try {
      const { url } = await api.gmailAuthorize();
      window.location.href = url;
    } catch (e) {
      setSyncResult(String(e));
    }
  };

  const handleDisconnect = async (provider: string) => {
    if (!confirm(`Disconnect ${provider}? This will revoke the authorization with Google.`)) return;
    try {
      await api.disconnectGmail();
      refetch();
    } catch (e) {
      setSyncResult(String(e));
    }
  };

  if (loading) return <PageLoader />;

  return (
    <div className="space-y-5 max-w-4xl mx-auto">
      <PageHeader
        title="Data Sources & Sync"
        subtitle="Transactions are collected automatically from connected sources. Sync runs while this device is on."
        actions={
          <button onClick={refetch} className="p-1.5 rounded"
            style={{ border: '1px solid var(--border-default)', color: 'var(--text-muted)' }}>
            <RefreshCw size={14} />
          </button>
        }
      />

      {/* Tabs */}
      <div className="flex gap-1 p-1 rounded-lg" style={{ background: 'var(--surface-2)', width: 'fit-content' }}>
        {(['sources', 'review'] as Tab[]).map(t => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className="px-4 py-1.5 rounded text-sm font-medium transition-colors"
            style={{
              background: tab === t ? 'var(--surface-0)' : 'transparent',
              color: tab === t ? 'var(--text-primary)' : 'var(--text-muted)',
              border: tab === t ? '1px solid var(--border-default)' : 'none',
              boxShadow: tab === t ? 'var(--shadow-sm)' : 'none',
            }}
          >
            {t === 'review' ? `Review queue${openReviews ? ` (${openReviews})` : ''}` : 'Sources'}
          </button>
        ))}
      </div>

      {syncResult && (
        <InfoBanner message={syncResult} />
      )}

      {tab === 'sources' && (
        <SourcesTab
          sources={sources ?? []}
          syncing={syncing}
          onSync={handleManualSync}
          onConnect={handleGmailConnect}
          onDisconnect={handleDisconnect}
        />
      )}

      {tab === 'review' && (
        <ReviewTab reviewData={reviewData} />
      )}
    </div>
  );
}

function SourcesTab({
  sources, syncing, onSync, onConnect, onDisconnect
}: {
  sources: SourceHealth[];
  syncing: boolean;
  onSync: (p: string) => void;
  onConnect: () => void;
  onDisconnect: (p: string) => void;
}) {
  const gmailSource = sources.find(s => s.provider === 'GMAIL');

  return (
    <div className="space-y-4">
      {/* Gmail */}
      <Card>
        <CardHeader>
          <SectionTitle>Gmail</SectionTitle>
        </CardHeader>
        <CardBody>
          {!gmailSource ? (
            <div className="space-y-4">
              <InfoBanner message="Gmail is not connected. Connect it to automatically collect bank alerts, card notifications, UPI transactions, and merchant receipts from your inbox." />
              <WarningBanner message="You must first create a Google Cloud OAuth client and set GMAIL_CLIENT_ID and GMAIL_CLIENT_SECRET. See the README for exact steps." />
              <Button variant="primary" size="sm" onClick={onConnect}>
                <ExternalLink size={14} /> Connect Gmail
              </Button>
            </div>
          ) : (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <SourceStatusIcon status={gmailSource.status} />
                    <p className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                      {gmailSource.displayName ?? 'Gmail'}
                    </p>
                    <SourceBadge status={gmailSource.status} />
                  </div>
                  <p className="text-xs mt-1" style={{ color: 'var(--text-muted)' }}>
                    {gmailSource.lastSuccessAt
                      ? `Last sync: ${new Date(gmailSource.lastSuccessAt).toLocaleString('en-IN')}`
                      : 'Never synced'}
                    {!gmailSource.backfillComplete && gmailSource.backfillThrough
                      ? ` · Importing back to ${gmailSource.backfillThrough}`
                      : gmailSource.backfillComplete ? ' · Historical import complete' : ''}
                  </p>
                  {gmailSource.lastError && (
                    <p className="text-xs mt-0.5" style={{ color: 'var(--color-error)' }}>
                      {gmailSource.lastError}
                    </p>
                  )}
                </div>
                <div className="flex items-center gap-2">
                  {gmailSource.status === 'CONNECTED' && (
                    <Button
                      variant="secondary" size="sm"
                      loading={syncing}
                      onClick={() => onSync('gmail')}
                    >
                      <RefreshCw size={14} /> Sync now
                    </Button>
                  )}
                  {gmailSource.status === 'NEEDS_REAUTH' && (
                    <Button variant="secondary" size="sm" onClick={onConnect}>
                      <ExternalLink size={14} /> Reconnect
                    </Button>
                  )}
                  <Button
                    variant="ghost" size="sm"
                    onClick={() => onDisconnect('gmail')}
                    className="text-red-500"
                  >
                    <Trash2 size={14} /> Disconnect
                  </Button>
                </div>
              </div>

              <div className="rounded-lg p-3" style={{ background: 'var(--surface-2)', border: '1px solid var(--border-default)' }}>
                <p className="text-xs font-medium mb-1" style={{ color: 'var(--text-secondary)' }}>Coverage note</p>
                <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                  Gmail collects bank alerts, card notifications, UPI receipts and merchant confirmations.
                  Not every email describes a transaction — OTPs, statements and marketing are filtered.
                  Cash, unsupported banks, and failed parses may be missing.
                  Sync requires this device to be on.
                </p>
              </div>
            </div>
          )}
        </CardBody>
      </Card>

      {/* Simulated provider (shows only when data is present) */}
      {sources.find(s => s.provider === 'SIMULATED') && (
        <Card>
          <CardHeader>
            <SectionTitle>Simulated feed (test/development only)</SectionTitle>
          </CardHeader>
          <CardBody>
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
              Replays synthetic messages for testing the ingestion pipeline without real credentials.
              Not a user-facing data source.
            </p>
          </CardBody>
        </Card>
      )}

      {/* Future integrations */}
      <Card>
        <CardHeader>
          <SectionTitle>Future integrations</SectionTitle>
        </CardHeader>
        <CardBody>
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            Additional providers will be added here as they are supported:
            official bank APIs, consent-based account aggregators,
            an Android SMS companion, and statement file imports for reconciliation.
            SMS and notification access on iOS is not available to web apps.
          </p>
        </CardBody>
      </Card>
    </div>
  );
}

function ReviewTab({ reviewData }: { reviewData: any }) {
  const items = reviewData?.items ?? [];

  return (
    <Card>
      <CardHeader>
        <SectionTitle>Items needing review</SectionTitle>
      </CardHeader>
      {items.length === 0 ? (
        <CardBody>
          <EmptyState
            icon={CheckCircle2}
            title="No items in the queue"
            body="Ambiguous matches, parse failures and uncategorized transactions appear here."
          />
        </CardBody>
      ) : (
        <Table>
          <thead>
            <tr>
              <Th>Kind</Th>
              <Th>Summary</Th>
              <Th>Created</Th>
              <Th>Action</Th>
            </tr>
          </thead>
          <tbody>
            {items.map((item: any) => (
              <Tr key={item.id}>
                <Td>
                  <ReviewKindBadge kind={item.kind} />
                </Td>
                <Td>
                  <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>{item.summary}</p>
                </Td>
                <Td>
                  <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                    {new Date(item.createdAt).toLocaleDateString('en-IN')}
                  </span>
                </Td>
                <Td>
                  <Button
                    variant="ghost" size="sm"
                    onClick={() => api.resolveReview(item.id, 'DISMISS').then(() => {})}
                  >
                    Dismiss
                  </Button>
                </Td>
              </Tr>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  );
}

function SourceStatusIcon({ status }: { status: string }) {
  if (status === 'CONNECTED') return <CheckCircle2 size={16} style={{ color: 'var(--color-success)' }} />;
  if (status === 'NEEDS_REAUTH') return <AlertTriangle size={16} style={{ color: 'var(--color-warning)' }} />;
  if (status === 'ERROR') return <XCircle size={16} style={{ color: 'var(--color-error)' }} />;
  return null;
}

function SourceBadge({ status }: { status: string }) {
  if (status === 'CONNECTED') return <Badge variant="success">Connected</Badge>;
  if (status === 'NEEDS_REAUTH') return <Badge variant="warning">Needs reconnect</Badge>;
  if (status === 'ERROR') return <Badge variant="error">Error</Badge>;
  if (status === 'NOT_CONFIGURED') return <Badge variant="muted">Not configured</Badge>;
  return <Badge>{status}</Badge>;
}

function ReviewKindBadge({ kind }: { kind: string }) {
  const map: Record<string, any> = {
    AMBIGUOUS_MATCH: ['Ambiguous', 'warning'],
    PARSE_FAILURE: ['Parse failure', 'error'],
    UNKNOWN_CATEGORY: ['Uncategorized', 'muted'],
    UNKNOWN_ACCOUNT: ['Unknown account', 'info'],
    RECONCILIATION_GAP: ['Gap', 'default'],
  };
  const [label, variant] = map[kind] ?? [kind, 'default'];
  return <Badge variant={variant}>{label}</Badge>;
}
