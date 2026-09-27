/**
 * Subscriptions: Play-verified entitlements + revenue summary.
 */
import { useMemo, useState } from 'react';
import { CreditCard, TrendingUp, Users as UsersIcon } from 'lucide-react';
import { api } from '../api/client';
import type { Subscription } from '../api/types';
import { usePaginatedQuery } from '../lib/hooks';
import { Badge, Card, ErrorNotice, PageHeader, Select, StatCard, Table, type TableColumn } from '../components/ui';
import { formatDate, formatNumber, formatUsd, timeAgo } from '../lib/format';

const STATUS_FILTERS = ['', 'ACTIVE', 'PENDING', 'EXPIRED', 'CANCELLED', 'GRACE'] as const;

/** The SPA uses startedAt/lastVerifiedAt; the worker sends startDate/lastVerified. */
function startedOf(s: Subscription): string {
  return s.startDate ?? s.startedAt;
}

function verifiedOf(s: Subscription): string | null {
  return s.lastVerified ?? s.lastVerifiedAt;
}

export default function SubscriptionsPage(): JSX.Element {
  const [status, setStatus] = useState<string>('');
  const queryKey = status;

  const { rows, loading, error, reload, page, hasPrev, hasNext, prev, next } = usePaginatedQuery<Subscription>(
    queryKey,
    (cursor) => api.listSubscriptions({ status: status || undefined, cursor, limit: 50 }),
  );

  const activeCount = rows.filter((s) => s.status === 'ACTIVE').length;
  // Only compute MRR from rows that actually carry a price — summing missing
  // prices would render "$NaN". When no row is priced (worker shape), show '—'.
  const hasPrices = rows.some((s) => s.priceUsd != null);
  const mrrFromPage = hasPrices
    ? formatUsd(rows.filter((s) => s.status === 'ACTIVE').reduce((sum, s) => sum + (s.priceUsd ?? 0), 0))
    : '—';

  const columns: Array<TableColumn<Subscription>> = useMemo(
    () => [
      { key: 'user', header: 'User', render: (s) => <span className="font-medium text-ink">{s.userEmail}</span> },
      { key: 'product', header: 'Product', render: (s) => <span className="font-mono text-xs text-ink2">{s.productId}</span> },
      { key: 'plan', header: 'Plan', render: (s) => <Badge tone="accent">{s.plan}</Badge> },
      {
        key: 'status',
        header: 'Status',
        render: (s) => (
          <Badge tone={s.status === 'ACTIVE' ? 'success' : s.status === 'GRACE' ? 'warning' : 'neutral'}>{s.status}</Badge>
        ),
      },
      { key: 'price', header: 'Price', align: 'right', render: (s) => <span className="tabular-nums">{s.priceUsd != null ? formatUsd(s.priceUsd) : '—'}</span> },
      { key: 'started', header: 'Started', render: (s) => <span className="text-ink2">{formatDate(startedOf(s))}</span> },
      { key: 'expiry', header: 'Expiry', render: (s) => <span className="text-ink2">{formatDate(s.expiryDate)}</span> },
      { key: 'verified', header: 'Last Verified', render: (s) => <span className="text-ink2">{timeAgo(verifiedOf(s))}</span> },
    ],
    [],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="Subscriptions"
        description="Google Play verified entitlements. Client-declared premium status is never trusted."
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Active (this page)" value={formatNumber(activeCount)} icon={<UsersIcon className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Page MRR" value={mrrFromPage} icon={<TrendingUp className="h-4 w-4" aria-hidden="true" />} accent="ok" loading={loading} />
        <StatCard label="Auto-renewing" value={formatNumber(rows.filter((s) => s.autoRenewing === true).length)} icon={<CreditCard className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="In Grace" value={formatNumber(rows.filter((s) => s.status === 'GRACE').length)} icon={<CreditCard className="h-4 w-4" aria-hidden="true" />} accent="warn" loading={loading} />
      </div>

      <div className="flex flex-col gap-3 sm:flex-row">
        <Select
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          aria-label="Filter by subscription status"
          className="sm:w-48"
        >
          {STATUS_FILTERS.map((s) => (
            <option key={s} value={s}>
              {s === '' ? 'All statuses' : s}
            </option>
          ))}
        </Select>
      </div>

      {error !== null ? <ErrorNotice error={error} onRetry={reload} /> : null}

      <Card padded={false}>
        <Table
          columns={columns}
          rows={rows}
          rowKey={(s) => s.id}
          loading={loading}
          emptyTitle="No subscriptions"
          emptyMessage="Play-verified subscriptions will appear here."
          pagination={{ page, hasPrev, hasNext, onPrev: prev, onNext: next, loadedCount: rows.length }}
        />
      </Card>
    </div>
  );
}
