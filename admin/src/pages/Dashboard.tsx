/**
 * Dashboard: top metrics, system health, recent audit.
 * Live Operations: auto-refreshing operational snapshot.
 */
import { useEffect } from 'react';
import {
  Activity,
  AlertTriangle,
  CreditCard,
  Gauge,
  Server,
  Smartphone,
  UserPlus,
  Users as UsersIcon,
} from 'lucide-react';
import { api, IS_MOCK } from '../api/client';
import type { AuditLog } from '../api/types';
import { useApiData } from '../lib/hooks';
import {
  Badge,
  Card,
  ErrorNotice,
  HealthBadge,
  PageHeader,
  Skeleton,
  StatCard,
  Table,
  type TableColumn,
} from '../components/ui';
import { formatDateTime, formatNumber, formatPercent } from '../lib/format';

export default function DashboardPage(): JSX.Element {
  const { data, loading, error, reload } = useApiData(() => api.getOverview(), []);

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  const m = data?.metrics;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Dashboard"
        description={
          IS_MOCK
            ? 'Running on built-in mock data — set VITE_API_URL to connect the real Worker.'
            : 'Product health, growth and enforcement activity at a glance.'
        }
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Total Users" value={formatNumber(m?.totalUsers ?? 0)} icon={<UsersIcon className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Active (7d)" value={formatNumber(m?.activeUsers7d ?? 0)} icon={<Activity className="h-4 w-4" aria-hidden="true" />} accent="ok" loading={loading} />
        <StatCard label="Active Devices" value={formatNumber(m?.activeDevices ?? 0)} icon={<Smartphone className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Premium Users" value={formatNumber(m?.premiumUsers ?? 0)} icon={<CreditCard className="h-4 w-4" aria-hidden="true" />} accent="info" loading={loading} />
        <StatCard label="New Today" value={formatNumber(m?.newUsersToday ?? 0)} icon={<UserPlus className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Sessions Today" value={formatNumber(m?.sessionsToday ?? 0)} icon={<Gauge className="h-4 w-4" aria-hidden="true" />} accent="ok" loading={loading} />
        <StatCard label="API Requests (24h)" value={formatNumber(m?.apiRequests24h ?? 0)} icon={<Server className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard
          label="Error Rate"
          value={formatPercent(m?.errorRate ?? 0)}
          icon={<AlertTriangle className="h-4 w-4" aria-hidden="true" />}
          accent={(m?.errorRate ?? 0) > 5 ? 'bad' : 'accent'}
          loading={loading}
        />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card title="System Health" description="Live probes from the Worker.">
          <ul className="space-y-3">
            {(['api', 'database', 'configService'] as const).map((key) => (
              <li key={key} className="flex items-center justify-between text-sm">
                <span className="capitalize text-ink2">
                  {key === 'configService' ? 'Config Service' : key === 'api' ? 'API' : 'Database'}
                </span>
                {loading ? <Skeleton className="h-5 w-20" /> : <HealthBadge status={data?.health[key] ?? 'down'} />}
              </li>
            ))}
          </ul>
        </Card>

        <div className="lg:col-span-2">
          <Card title="Recent Audit Activity" description="Latest administrative actions (append-only log).">
            <RecentAuditTable loading={loading} rows={data?.recentAudit ?? []} />
          </Card>
        </div>
      </div>
    </div>
  );
}

function RecentAuditTable({ loading, rows }: { loading: boolean; rows: AuditLog[] }): JSX.Element {
  const columns: Array<TableColumn<AuditLog>> = [
    { key: 'time', header: 'Time', render: (r) => <span className="text-ink2">{formatDateTime(r.createdAt)}</span> },
    { key: 'admin', header: 'Admin', render: (r) => <span className="font-medium">{r.adminEmail ?? r.adminId}</span> },
    {
      key: 'action',
      header: 'Action',
      render: (r) => <Badge tone="accent">{r.action}</Badge>,
    },
    { key: 'result', header: 'Result', render: (r) => <Badge tone={r.result === 'SUCCESS' ? 'success' : 'danger'}>{r.result}</Badge> },
  ];

  return (
    <Table
      columns={columns}
      rows={rows}
      rowKey={(r) => r.id}
      loading={loading}
      emptyTitle="No audit activity yet"
      emptyMessage="Sensitive admin actions will appear here."
    />
  );
}

// ---------------------------------------------------------------------------
// Live Operations
// ---------------------------------------------------------------------------

export function LiveOperationsPage(): JSX.Element {
  const { data, loading, error, reload } = useApiData(() => api.getOverview(), []);

  // Auto-refresh every 30 seconds (paused on hidden tabs by the browser
  // when throttled — harmless here since this is a monitoring view).
  useEffect(() => {
    const timer = window.setInterval(reload, 30_000);
    return () => window.clearInterval(timer);
  }, [reload]);

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  const m = data?.metrics;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Live Operations"
        description="Operational snapshot — refreshes automatically every 30 seconds."
        actions={<Badge tone="info" icon={<Activity className="h-3 w-3" aria-hidden="true" />}>Auto-refresh 30s</Badge>}
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Active Devices (7d)" value={formatNumber(m?.activeDevices ?? 0)} icon={<Smartphone className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Sessions Today" value={formatNumber(m?.sessionsToday ?? 0)} icon={<Gauge className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="API 24h" value={formatNumber(m?.apiRequests24h ?? 0)} icon={<Server className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Error Rate" value={formatPercent(m?.errorRate ?? 0)} icon={<AlertTriangle className="h-4 w-4" aria-hidden="true" />} accent={(m?.errorRate ?? 0) > 5 ? 'bad' : 'ok'} loading={loading} />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Service Health" description="Probe results from the edge Worker.">
          <ul className="space-y-3">
            {(['api', 'database', 'configService'] as const).map((key) => (
              <li key={key} className="flex items-center justify-between text-sm">
                <span className="capitalize text-ink2">
                  {key === 'configService' ? 'Config Service' : key === 'api' ? 'API' : 'Database'}
                </span>
                {loading ? <Skeleton className="h-5 w-20" /> : <HealthBadge status={data?.health[key] ?? 'down'} />}
              </li>
            ))}
          </ul>
        </Card>

        <Card title="Activity Stream" description="Most recent admin actions.">
          <RecentAuditTable loading={loading} rows={(data?.recentAudit ?? []).slice(0, 5)} />
        </Card>
      </div>
    </div>
  );
}
