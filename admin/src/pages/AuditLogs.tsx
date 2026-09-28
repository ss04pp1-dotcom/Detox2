/**
 * Audit Logs: filterable, append-only administrative trail.
 */
import { useMemo, useState } from 'react';
import { api } from '../api/client';
import { AUDIT_ACTIONS, type AuditLog } from '../api/types';
import { usePaginatedQuery } from '../lib/hooks';
import { Badge, Card, ErrorNotice, Input, PageHeader, Select, Table, type TableColumn } from '../components/ui';
import { formatDateTime } from '../lib/format';

export default function AuditLogsPage(): JSX.Element {
  const [adminId, setAdminId] = useState('');
  const [action, setAction] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const queryKey = `${adminId}|${action}|${from}|${to}`;

  const { rows, loading, error, reload, page, hasPrev, hasNext, prev, next } = usePaginatedQuery<AuditLog>(
    queryKey,
    (cursor) =>
      api.listAuditLogs({
        adminId: adminId.trim() || undefined,
        action: action || undefined,
        from: from ? new Date(from).toISOString() : undefined,
        to: to ? new Date(`${to}T23:59:59`).toISOString() : undefined,
        cursor,
        limit: 50,
      }),
  );

  const columns: Array<TableColumn<AuditLog>> = useMemo(
    () => [
      { key: 'time', header: 'Time', render: (l) => <span className="text-ink2">{formatDateTime(l.createdAt)}</span> },
      { key: 'admin', header: 'Admin', render: (l) => <span className="font-medium">{l.adminEmail ?? l.adminId}</span> },
      { key: 'action', header: 'Action', render: (l) => <Badge tone="accent">{l.action}</Badge> },
      { key: 'resource', header: 'Resource', render: (l) => <span className="text-ink2">{l.resourceType}{l.resourceId !== null ? ` · ${l.resourceId}` : ''}</span> },
      { key: 'result', header: 'Result', render: (l) => <Badge tone={l.result === 'SUCCESS' ? 'success' : 'danger'}>{l.result}</Badge> },
      { key: 'req', header: 'Request ID', render: (l) => <span className="font-mono text-xs text-ink2">{l.requestId}</span> },
    ],
    [],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="Audit Logs"
        description="Every sensitive admin action, append-only. UPDATE and DELETE are rejected by database triggers."
      />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Input type="search" value={adminId} onChange={(e) => setAdminId(e.target.value)} placeholder="Filter by admin ID…" aria-label="Filter by admin ID" />
        <Select value={action} onChange={(e) => setAction(e.target.value)} aria-label="Filter by action">
          <option value="">All actions</option>
          {AUDIT_ACTIONS.map((a) => (
            <option key={a} value={a}>{a}</option>
          ))}
        </Select>
        <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From date" />
        <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="To date" />
      </div>

      {error !== null ? <ErrorNotice error={error} onRetry={reload} /> : null}

      <Card padded={false}>
        <Table
          columns={columns}
          rows={rows}
          rowKey={(l) => l.id}
          loading={loading}
          emptyTitle="No audit entries"
          emptyMessage="Try widening the filters."
          pagination={{ page, hasPrev, hasNext, onPrev: prev, onNext: next, loadedCount: rows.length }}
        />
      </Card>
    </div>
  );
}
