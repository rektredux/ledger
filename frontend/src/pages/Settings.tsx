import { useState } from 'react';
import { Download, Upload, HardDrive } from 'lucide-react';
import {
  Card, CardHeader, CardBody, PageHeader, SectionTitle, Button,
  WarningBanner, InfoBanner, SuccessBanner, ErrorBanner
} from '../components/Layout';

type Status = { ok: boolean; message: string } | null;

export default function Settings() {
  const [restoreStatus, setRestoreStatus] = useState<Status>(null);
  const [csvStatus, setCsvStatus] = useState<Status>(null);

  const handleBackup = () => {
    window.location.href = '/api/data/backup';
  };

  const handleExportCsv = () => {
    window.location.href = '/api/data/csv/export';
  };

  const handleCsvUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    try {
      const res = await fetch('/api/data/csv/preview', { method: 'POST', body: form });
      if (!res.ok) throw new Error(await res.text());
      const preview = await res.json();
      setCsvStatus({ ok: true, message: `Preview: ${preview.totalRows} rows, ${preview.validRows?.length ?? 0} valid, ${preview.errors?.length ?? 0} errors. Commit not yet implemented in this interface.` });
    } catch (err) {
      setCsvStatus({ ok: false, message: String(err) });
    }
  };

  const handleRestoreUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!confirm('Restore (MERGE mode) from this backup? This will add records not already present. Existing data will not be deleted.')) return;
    const form = new FormData();
    form.append('file', file);
    form.append('mode', 'MERGE');
    try {
      const res = await fetch('/api/data/backup/restore', { method: 'POST', body: form });
      if (!res.ok) throw new Error(await res.text());
      const report = await res.json();
      setRestoreStatus({
        ok: true,
        message: `Restored: ${report.transactionsRestored} transactions, ${report.sourceRecordsRestored} source records, ${report.skippedExisting} skipped (already present). ${report.warnings?.join(' ')}`,
      });
    } catch (err) {
      setRestoreStatus({ ok: false, message: String(err) });
    }
  };

  return (
    <div className="space-y-6 max-w-2xl mx-auto">
      <PageHeader
        title="Settings & Backup"
        subtitle="Data management, imports, exports and backup."
      />

      {/* Data backup */}
      <Card>
        <CardHeader>
          <SectionTitle>Backup & restore</SectionTitle>
        </CardHeader>
        <CardBody className="space-y-4">
          <InfoBanner message="The backup file contains your complete ledger: transactions, accounts, categories, rules, budgets and recurring definitions. Provider tokens (Gmail authorization) are never included." />

          <div className="flex flex-col sm:flex-row gap-3">
            <Button variant="secondary" size="md" onClick={handleBackup}>
              <Download size={14} /> Download backup (JSON)
            </Button>
            <label className="cursor-pointer inline-flex items-center gap-2 px-4 py-2 text-sm font-medium rounded"
              style={{ background: 'transparent', color: 'var(--text-secondary)', border: 'none', cursor: 'pointer' }}>
              <Upload size={14} /> Restore from backup
              <input type="file" accept=".json" className="hidden" onChange={handleRestoreUpload} />
            </label>
          </div>

          {restoreStatus && (
            restoreStatus.ok
              ? <SuccessBanner message={restoreStatus.message} />
              : <ErrorBanner message={restoreStatus.message} />
          )}

          <WarningBanner message="The SQLite database file (backend/data/ledger.db) is the authoritative copy. Stop the backend before copying it for a full backup. See README for instructions." />
        </CardBody>
      </Card>

      {/* CSV import/export */}
      <Card>
        <CardHeader>
          <SectionTitle>CSV import & export</SectionTitle>
        </CardHeader>
        <CardBody className="space-y-4">
          <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
            CSV import is a fallback for backfilling and reconciliation.
            Imported rows go through the same deduplication pipeline as Gmail — they will be
            linked to existing transactions rather than creating duplicates.
          </p>

          <div className="flex flex-col sm:flex-row gap-3">
            <Button variant="secondary" size="md" onClick={handleExportCsv}>
              <Download size={14} /> Export all transactions (CSV)
            </Button>
            <a href="/api/data/csv/template" download="ledger-template.csv">
              <Button variant="ghost" size="md">
                <Download size={14} /> Download template
              </Button>
            </a>
            <label className="cursor-pointer inline-flex items-center gap-2 px-4 py-2 text-sm font-medium rounded"
              style={{ background: 'transparent', color: 'var(--text-secondary)', border: 'none', cursor: 'pointer' }}>
              <Upload size={14} /> Import CSV
              <input type="file" accept=".csv" className="hidden" onChange={handleCsvUpload} />
            </label>
          </div>

          {csvStatus && (
            csvStatus.ok
              ? <InfoBanner message={csvStatus.message} />
              : <ErrorBanner message={csvStatus.message} />
          )}
        </CardBody>
      </Card>

      {/* Database location */}
      <Card>
        <CardHeader>
          <SectionTitle>Local storage</SectionTitle>
        </CardHeader>
        <CardBody className="space-y-3">
          <div className="flex items-start gap-3 p-3 rounded-lg"
            style={{ background: 'var(--surface-2)', border: '1px solid var(--border-default)' }}>
            <HardDrive size={16} style={{ color: 'var(--text-muted)', flexShrink: 0, marginTop: 2 }} />
            <div>
              <p className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                backend/data/ledger.db
              </p>
              <p className="text-xs mt-1" style={{ color: 'var(--text-muted)' }}>
                SQLite database containing your complete financial history in plaintext.
                Restrict OS account permissions and store encrypted backups.
                Override location with <code>LEDGER_DB_PATH</code>.
              </p>
            </div>
          </div>
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            To migrate to PostgreSQL: change the datasource URL, Flyway configuration and
            Hibernate dialect in application.properties. Keep repositories behind their
            interfaces — no SQL changes are required in application code.
          </p>
        </CardBody>
      </Card>
    </div>
  );
}
