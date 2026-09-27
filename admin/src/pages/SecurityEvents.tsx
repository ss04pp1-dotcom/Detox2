/**
 * Security Events: severity-filtered event feed with expandable metadata.
 */
import { useMemo, useState } from 'react';
import { api } from '../api/client';
import type { SecurityEvent } from '../api/types';
import { usePaginatedQuery } from '../lib/hooks';
import { Card, ErrorNotice, PageHeader, Select, SeverityBadge, Table, type TableColumn } from '../components/ui';
import { formatDateTime } from '../lib/format';

const SEVERITIES = ['', 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const;

export default function SecurityEventsPage(): JSX.Element {
  const [severity, setSeverity] = useState('');
  const queryKey = severity;

  const { rows, loading, error, reload, page, hasPrev, hasNext, prev, next } = usePaginatedQuery<SecurityEvent>(
    queryKey,
    (cursor) => api.listSecurityEvents({ severity: severity || undefined, cursor, limit: 50 }),
  );

  const [expanded, setExpanded] = useState<string | null>(null);

  const columns: Array<TableColumn<SecurityEvent>> = useMemo(
    () => [
      { key: 'time', header: 'Time', render: (e) => <span className="text-ink2">{formatDateTime(e.createdAt)}</span> },
      { key: 'type', header: 'Event', render: (e) => <span className="font-medium">{e.eventType ?? e.type}</span> },
      { key: 'severity', header: 'Severity', render: (e) => <SeverityBadge severity={e.severity} /> },
      { key: 'user', header: 'User', render: (e) => <span className="text-ink2">{e.userEmail ?? e.userId ?? '—'}</span> },
      {
        key: 'meta',
        header: 'Metadata',
        render: (e) =>
          Object.keys(e.metadata).length === 0 ? (
            <span className="text-ink2">—</span>
          ) : (
            <button
              type="button"
              className="text-xs font-medium text-accent hover:underline"
              onClick={() => setExpanded(expanded === e.id ? null : e.id)}
            >
              {expanded === e.id ? 'Hide' : 'View'}
            </button>
          ),
      },
    ],
    [expanded],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="Security Events"
        description="Auth failures, token reuse, suspicious reward activity and abuse signals. Heuristics flag — they never auto-punish without review."
      />

      <Select value={severity} onChange={(e) => setSeverity(e.target.value)} aria-label="Filter by severity" className="max-w-44">
        {SEVERITIES.map((s) => (
          <option key={s} value={s}>
            {s === '' ? 'All severities' : s}
          </option>
        ))}
      </Select>

      {error !== null ? <ErrorNotice error={error} onRetry={reload} /> : null}

      <Card padded={false}>
        <Table
          columns={columns}
          rows={rows}
          rowKey={(e) => e.id}
          loading={loading}
          emptyTitle="No security events"
          emptyMessage="A quiet system is a good system."
          pagination={{ page, hasPrev, hasNext, onPrev: prev, onNext: next, loadedCount: rows.length }}
        />
      </Card>

      {expanded !== null ? (
        <Card title="Event Metadata" description={`Metadata for ${expanded}`}>
          {(() => {
            const row = rows.find((r) => r.id === expanded);
            if (row === undefined) return <p className="text-sm text-ink2">Event is not on this page anymore.</p>;
            return (
              <pre className="max-h-64 overflow-auto rounded-lg border border-edge bg-page p-4 text-xs text-ink2">
                {JSON.stringify(row.metadata, null, 2)}
              </pre>
            );
          })()}
        </Card>
      ) : null}
    </div>
  );
}
