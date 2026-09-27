/**
 * Analytics: Product (DAU, sessions funnel, retention), Enforcement
 * (shorts/cage/unlock events), Revenue (MRR, premium growth, plans).
 */
import { useState } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { Coins, TrendingUp, Users as UsersIcon } from 'lucide-react';
import { api } from '../api/client';
import type { AnalyticsRange } from '../api/types';
import { useApiData } from '../lib/hooks';
import { Card, ErrorNotice, PageHeader, Skeleton, StatCard, Table, type TableColumn } from '../components/ui';
import { formatNumber, formatPercent, formatUsd } from '../lib/format';

const RANGES: AnalyticsRange[] = ['7d', '30d', '90d'];

function RangePicker({ value, onChange }: { value: AnalyticsRange; onChange: (r: AnalyticsRange) => void }): JSX.Element {
  return (
    <div className="flex gap-1 rounded-lg border border-edge bg-surface p-1" role="group" aria-label="Date range">
      {RANGES.map((r) => (
        <button
          key={r}
          type="button"
          onClick={() => onChange(r)}
          className={
            'rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ' +
            (value === r ? 'bg-accent/15 text-accent' : 'text-ink2 hover:text-ink')
          }
          aria-pressed={value === r}
        >
          {r}
        </button>
      ))}
    </div>
  );
}

const TOOLTIP_STYLE = {
  backgroundColor: '#111A2E',
  border: '1px solid #1E293B',
  borderRadius: 8,
  color: '#E2E8F0',
  fontSize: 12,
};

function DauChart({ dau }: { dau: Array<{ date: string; value: number }> }): JSX.Element {
  return (
    <ResponsiveContainer width="100%" height={260}>
      <LineChart data={dau} margin={{ top: 8, right: 12, bottom: 0, left: -16 }}>
        <CartesianGrid stroke="#1E293B" strokeDasharray="3 3" />
        <XAxis dataKey="date" stroke="#94A3B8" fontSize={11} tickLine={false} />
        <YAxis stroke="#94A3B8" fontSize={11} tickLine={false} />
        <Tooltip contentStyle={TOOLTIP_STYLE} />
        <Line type="monotone" dataKey="value" name="Active devices" stroke="#6366F1" strokeWidth={2} dot={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}

export function ProductAnalyticsPage(): JSX.Element {
  const [range, setRange] = useState<AnalyticsRange>('30d');
  const { data, loading, error, reload } = useApiData(() => api.getAnalytics(range), [range]);

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  // Cohort rows (mock/SPA contract) or the worker's single weekly summary.
  const retention = data?.retention;

  const funnel = data === null ? [] : [
    { stage: 'Started', value: data.sessions.started },
    { stage: 'Completed', value: data.sessions.completed },
    { stage: 'Bailed out', value: data.sessions.bailed },
  ];

  return (
    <div className="space-y-6">
      <PageHeader title="Product Analytics" description="Aggregate product events only — no raw screen content is ever collected." actions={<RangePicker value={range} onChange={setRange} />} />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Sessions Started" value={formatNumber(data?.sessions.started ?? 0)} icon={<TrendingUp className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Sessions Completed" value={formatNumber(data?.sessions.completed ?? 0)} accent="ok" loading={loading} />
        <StatCard label="Bailouts" value={formatNumber(data?.sessions.bailed ?? 0)} accent="bad" loading={loading} />
        <StatCard label="Completion Rate" value={formatPercent(data !== null && data.sessions.started > 0 ? (data.sessions.completed / data.sessions.started) * 100 : 0)} loading={loading} />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Daily Active Devices" description="Distinct devices seen via events.">
          {loading ? <Skeleton className="h-64" /> : <DauChart dau={data?.dau ?? []} />}
        </Card>
        <Card title="Session Funnel" description="Started → Completed → Bailed out.">
          {loading ? (
            <Skeleton className="h-64" />
          ) : (
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={funnel} margin={{ top: 8, right: 12, bottom: 0, left: -16 }}>
                <CartesianGrid stroke="#1E293B" strokeDasharray="3 3" />
                <XAxis dataKey="stage" stroke="#94A3B8" fontSize={11} tickLine={false} />
                <YAxis stroke="#94A3B8" fontSize={11} tickLine={false} />
                <Tooltip contentStyle={TOOLTIP_STYLE} />
                <Bar dataKey="value" name="Sessions" fill="#6366F1" radius={[6, 6, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </Card>
      </div>

      <Card title="Retention Cohorts" description="Percentage of each cohort still active.">
        {retention !== undefined && Array.isArray(retention) ? (
          <RetentionTable rows={retention} />
        ) : (
          // The worker currently returns a single weekly-active summary
          // object instead of cohort rows — render it instead of crashing.
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <span className="text-ink2">Weekly active devices:</span>
            <span className="text-2xl font-bold tabular-nums text-ink">{formatNumber(retention?.weeklyActiveDevices ?? 0)}</span>
            <span className="text-xs text-ink2">Cohort breakdown appears once cohort data is available.</span>
          </div>
        )}
      </Card>
    </div>
  );
}

function RetentionTable({ rows }: { rows: Array<{ cohort: string; cohortSize: number; day1: number; day7: number; day30: number }> }): JSX.Element {
  const columns: Array<TableColumn<{ cohort: string; cohortSize: number; day1: number; day7: number; day30: number }>> = [
    { key: 'cohort', header: 'Cohort', render: (r) => <span className="font-medium">{r.cohort}</span> },
    { key: 'size', header: 'Size', align: 'right', render: (r) => <span className="tabular-nums">{formatNumber(r.cohortSize)}</span> },
    { key: 'd1', header: 'Day 1', align: 'right', render: (r) => <span className="tabular-nums">{formatPercent(r.day1)}</span> },
    { key: 'd7', header: 'Day 7', align: 'right', render: (r) => <span className="tabular-nums">{formatPercent(r.day7)}</span> },
    { key: 'd30', header: 'Day 30', align: 'right', render: (r) => <span className="tabular-nums">{formatPercent(r.day30)}</span> },
  ];
  return <Table columns={columns} rows={rows} rowKey={(r) => r.cohort} emptyTitle="No cohort data yet" emptyMessage="Cohorts appear once enough users join." />;
}

// ---------------------------------------------------------------------------
// Enforcement analytics
// ---------------------------------------------------------------------------

export function EnforcementAnalyticsPage(): JSX.Element {
  const [range, setRange] = useState<AnalyticsRange>('30d');
  const { data, loading, error, reload } = useApiData(() => api.getAnalytics(range), [range]);

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  const events = data === null ? [] : [
    { kind: 'Shorts warnings', value: data.shorts.warnings },
    { kind: 'Cage activations', value: data.shorts.cages },
    { kind: 'Temporary unlocks', value: data.tempUnlocks },
    { kind: 'Bailouts', value: data.sessions.bailed },
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        title="Enforcement Analytics"
        description="Aggregate enforcement events (SHORTS_WARNING, CAGE_ACTIVATED, TEMP_UNLOCK_*…). Raw screen contents are never transmitted."
        actions={<RangePicker value={range} onChange={setRange} />}
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Shorts Warnings" value={formatNumber(data?.shorts.warnings ?? 0)} accent="warn" loading={loading} />
        <StatCard label="Cage Activations" value={formatNumber(data?.shorts.cages ?? 0)} accent="bad" loading={loading} />
        <StatCard label="Temporary Unlocks" value={formatNumber(data?.tempUnlocks ?? 0)} icon={<Coins className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Bailouts" value={formatNumber(data?.sessions.bailed ?? 0)} accent="bad" loading={loading} />
      </div>

      <Card title="Enforcement Events" description="Volume by event kind in the selected range.">
        {loading ? (
          <Skeleton className="h-64" />
        ) : (
          <ResponsiveContainer width="100%" height={280}>
            <BarChart data={events} margin={{ top: 8, right: 12, bottom: 0, left: -16 }}>
              <CartesianGrid stroke="#1E293B" strokeDasharray="3 3" />
              <XAxis dataKey="kind" stroke="#94A3B8" fontSize={11} tickLine={false} />
              <YAxis stroke="#94A3B8" fontSize={11} tickLine={false} />
              <Tooltip contentStyle={TOOLTIP_STYLE} />
              <Bar dataKey="value" name="Events" fill="#8B5CF6" radius={[6, 6, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        )}
      </Card>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Revenue
// ---------------------------------------------------------------------------

export function RevenueAnalyticsPage(): JSX.Element {
  const [range, setRange] = useState<AnalyticsRange>('30d');
  const { data, loading, error, reload } = useApiData(() => api.getAnalytics(range), [range]);

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  // v2.5.7 (A-4): the worker now computes a REAL plan-catalog-derived
  // monthly recurring figure in BDT paisa (smallest unit). Show it in
  // taka; the old USD fallbacks only remain for the mock dataset.
  const revenue = data?.revenue;
  const bdtMinor = revenue?.estMonthlyBdt;
  const mrrDisplay =
    bdtMinor !== undefined && bdtMinor !== null
      ? `৳ ${formatNumber(Math.round(bdtMinor / 100))}`
      : (revenue?.mrrEstimateUsd ?? revenue?.estMonthlyUsd) !== undefined &&
          (revenue?.mrrEstimateUsd ?? revenue?.estMonthlyUsd) !== null
        ? formatUsd(revenue?.mrrEstimateUsd ?? revenue?.estMonthlyUsd ?? 0)
        : '—';

  return (
    <div className="space-y-6">
      <PageHeader title="Revenue" description="Play-verified subscription revenue estimates (plan catalog, BDT)." actions={<RangePicker value={range} onChange={setRange} />} />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="MRR Estimate (BDT)" value={mrrDisplay} icon={<TrendingUp className="h-4 w-4" aria-hidden="true" />} accent="ok" loading={loading} />
        <StatCard label="Premium Users" value={formatNumber(data?.revenue?.premiumUsers ?? 0)} icon={<UsersIcon className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Monthly Plans" value={formatNumber(data?.revenue?.byPlan?.find((p) => p.plan === 'MONTHLY')?.subscribers ?? 0)} loading={loading} />
        <StatCard label="Yearly Plans" value={formatNumber(data?.revenue?.byPlan?.find((p) => p.plan === 'YEARLY')?.subscribers ?? 0)} loading={loading} />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Premium Growth" description="Premium subscribers over time.">
          {loading ? <Skeleton className="h-64" /> : <DauChart dau={data?.revenue?.premiumGrowth ?? []} />}
        </Card>
        <Card title="By Plan">
          <Table
            columns={[
              { key: 'plan', header: 'Plan', render: (p: { plan: string; subscribers: number; monthlyValueUsd: number }) => <span className="font-medium">{p.plan}</span> },
              { key: 'subs', header: 'Subscribers', align: 'right', render: (p) => <span className="tabular-nums">{formatNumber(p.subscribers)}</span> },
              { key: 'value', header: 'Monthly value', align: 'right', render: (p) => <span className="tabular-nums">{formatUsd(p.monthlyValueUsd)}</span> },
            ]}
            rows={data?.revenue?.byPlan ?? []}
            rowKey={(p) => p.plan}
            emptyTitle="No subscriptions yet"
            emptyMessage="Revenue appears once premium launches."
          />
        </Card>
      </div>
    </div>
  );
}
