/**
 * Devices: registered installations with risk indicators.
 */
import { useMemo, useState } from 'react';
import { api } from '../api/client';
import type { Device } from '../api/types';
import { usePaginatedQuery } from '../lib/hooks';
import { Badge, Card, ErrorNotice, Input, PageHeader, Table, type TableColumn } from '../components/ui';
import { timeAgo } from '../lib/format';

function RiskBadge({ risk }: { risk: Device['riskLevel'] }): JSX.Element {
  // The worker serializer does not send riskLevel — render a neutral dash
  // instead of a false "Low risk" safety signal.
  if (risk === undefined || risk === null) return <span className="text-ink2">—</span>;
  const tone = risk === 'HIGH' ? 'danger' : risk === 'MEDIUM' ? 'warning' : 'success';
  const label = risk === 'LOW' ? 'Low risk' : risk === 'MEDIUM' ? 'Medium risk' : 'High risk';
  return <Badge tone={tone}>{label}</Badge>;
}

export default function DevicesPage(): JSX.Element {
  const [search, setSearch] = useState('');
  const queryKey = search;

  const { rows, loading, error, reload, page, hasPrev, hasNext, prev, next } = usePaginatedQuery<Device>(
    queryKey,
    (cursor) => api.listDevices({ q: search.trim() || undefined, cursor, limit: 50 }),
  );

  const columns: Array<TableColumn<Device>> = useMemo(
    () => [
      { key: 'id', header: 'Device ID', render: (d) => <span className="font-mono text-xs text-ink2">{d.id}</span> },
      {
        key: 'device',
        header: 'Model',
        render: (d) => (
          <div>
            <p className="font-medium text-ink">{d.model}</p>
            <p className="text-xs text-ink2">{d.manufacturer}</p>
          </div>
        ),
        sortValue: (d) => d.model,
      },
      { key: 'user', header: 'User', render: (d) => <span className="text-ink2">{d.userEmail}</span> },
      { key: 'android', header: 'Android', render: (d) => <span className="text-ink2">{d.androidVersion}</span> },
      { key: 'app', header: 'App', render: (d) => <Badge tone="neutral">{d.appVersion}</Badge> },
      { key: 'seen', header: 'Last Seen', render: (d) => <span className="text-ink2">{timeAgo(d.lastSeenAt)}</span> },
      { key: 'status', header: 'Status', render: (d) => <Badge tone={d.status === 'ACTIVE' ? 'success' : 'neutral'}>{d.status}</Badge> },
      { key: 'risk', header: 'Risk', render: (d) => <RiskBadge risk={d.riskLevel} /> },
    ],
    [],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="Devices"
        description="Registered installations. Raw permission/accessibility content is never uploaded — only aggregate permission summaries."
      />

      <form
        className="relative max-w-md"
        onSubmit={(e) => {
          e.preventDefault();
          reload();
        }}
        role="search"
      >
        <Input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by model, device ID or user email…"
          aria-label="Search devices"
        />
      </form>

      {error !== null ? <ErrorNotice error={error} onRetry={reload} /> : null}

      <Card padded={false}>
        <Table
          columns={columns}
          rows={rows}
          rowKey={(d) => d.id}
          loading={loading}
          emptyTitle="No devices found"
          emptyMessage="Devices appear here after users register them."
          pagination={{ page, hasPrev, hasNext, onPrev: prev, onNext: next, loadedCount: rows.length }}
        />
      </Card>
    </div>
  );
}
