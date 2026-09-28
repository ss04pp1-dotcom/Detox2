/**
 * Users list (search + status filter + cursor pagination) and user detail
 * (profile, subscription, devices, security events, tickets, admin actions).
 */
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Coins, ShieldOff, ShieldCheck } from 'lucide-react';
import { api } from '../api/client';
import type { Device, SecurityEvent, Ticket, User, UserPlan, UserStatus } from '../api/types';
import { useApiData, useDebounced, usePaginatedQuery } from '../lib/hooks';
import { useAuth } from '../auth/AuthContext';
import {
  Badge,
  Button,
  Card,
  ConfirmModal,
  ErrorNotice,
  Field,
  Input,
  Modal,
  PageHeader,
  Select,
  SeverityBadge,
  Skeleton,
  Table,
  Textarea,
  UserStatusBadge,
  useToast,
  type TableColumn,
} from '../components/ui';
import { formatDate, formatDateTime, timeAgo } from '../lib/format';

const STATUS_FILTERS: Array<{ value: string; label: string }> = [
  { value: '', label: 'All statuses' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'SUSPENDED', label: 'Suspended' },
  { value: 'BANNED', label: 'Banned' },
];

/** The worker sends the subscription plan ('MONTHLY'/'YEARLY'/null) rather than
 *  the SPA's FREE/PREMIUM pair — map any truthy plan to PREMIUM. */
function PlanBadge({ plan }: { plan: UserPlan }): JSX.Element {
  const premium = plan !== null && plan !== 'FREE';
  return <Badge tone={premium ? 'accent' : 'neutral'}>{premium ? 'PREMIUM' : 'FREE'}</Badge>;
}

export function UsersPage(): JSX.Element {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') ?? '');
  const [status, setStatus] = useState('');

  // Global search (Layout topbar) navigates to /users?q=… — sync the local
  // search state from the URL so it works while this page is already mounted.
  useEffect(() => {
    const q = params.get('q');
    if (q !== null) setSearch(q);
  }, [params]);

  // Debounce the query key so typing does not fire a request per keystroke.
  const debouncedSearch = useDebounced(search, 300);

  const queryKey = `${debouncedSearch}|${status}`;
  const { rows, loading, error, reload, page, hasPrev, hasNext, prev, next } = usePaginatedQuery<User>(
    queryKey,
    (cursor) => api.listUsers({ q: debouncedSearch.trim() || undefined, status: status || undefined, cursor, limit: 50 }),
  );

  const columns: Array<TableColumn<User>> = useMemo(
    () => [
      { key: 'email', header: 'Email', render: (u) => <span className="font-medium text-ink">{u.email}</span>, sortValue: (u) => u.email },
      { key: 'status', header: 'Status', render: (u) => <UserStatusBadge status={u.status} /> },
      { key: 'plan', header: 'Plan', render: (u) => <PlanBadge plan={u.plan} /> },
      { key: 'devices', header: 'Devices', align: 'right', render: (u) => formatCount(u.deviceCount), sortValue: (u) => u.deviceCount },
      {
        key: 'coins',
        header: 'Coins',
        align: 'right',
        render: (u) => (u.coinBalance == null ? <span className="text-ink2">—</span> : formatCount(u.coinBalance)),
        sortValue: (u) => u.coinBalance ?? 0,
      },
      { key: 'lastSeen', header: 'Last Seen', render: (u) => <span className="text-ink2">{timeAgo(u.lastSeenAt)}</span>, sortValue: (u) => u.lastSeenAt ?? '' },
      { key: 'created', header: 'Created', render: (u) => <span className="text-ink2">{formatDate(u.createdAt)}</span>, sortValue: (u) => u.createdAt },
    ],
    [],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="Users"
        description="Search by email, inspect devices, subscriptions and security events."
      />

      <div className="flex flex-col gap-3 sm:flex-row">
        <form
          className="relative flex-1"
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
            placeholder="Search by email…"
            aria-label="Search users by email"
          />
        </form>
        <Select
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          aria-label="Filter by status"
          className="sm:w-44"
        >
          {STATUS_FILTERS.map((f) => (
            <option key={f.value} value={f.value}>
              {f.label}
            </option>
          ))}
        </Select>
      </div>

      {error !== null ? <ErrorNotice error={error} onRetry={reload} /> : null}

      <Card padded={false}>
        <Table
          columns={columns}
          rows={rows}
          rowKey={(u) => u.id}
          loading={loading}
          onRowClick={(u) => navigate(`/users/${encodeURIComponent(u.id)}`)}
          emptyTitle="No users found"
          emptyMessage={search.length > 0 ? 'Try a different search term.' : 'Users will appear here as the app grows.'}
          pagination={{ page, hasPrev, hasNext, onPrev: prev, onNext: next, loadedCount: rows.length }}
        />
      </Card>
    </div>
  );
}

function formatCount(n: number): JSX.Element {
  return <span className="tabular-nums">{n}</span>;
}

// ---------------------------------------------------------------------------
// User detail
// ---------------------------------------------------------------------------

export function UserDetailPage(): JSX.Element {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const toast = useToast();
  const { hasPermission } = useAuth();
  const { data, loading, error, reload } = useApiData(() => api.getUser(id), [id]);

  const [statusModal, setStatusModal] = useState<UserStatus | null>(null);
  const [statusBusy, setStatusBusy] = useState(false);
  const [coinModal, setCoinModal] = useState(false);

  const canEdit = hasPermission('EDIT_USERS');

  if (error !== null) {
    return (
      <div className="space-y-4">
        <Button variant="secondary" onClick={() => navigate('/users')}>Back to users</Button>
        <ErrorNotice error={error} onRetry={reload} />
      </div>
    );
  }

  const user = data?.user;

  const applyStatus = async (): Promise<void> => {
    if (statusModal === null || user == null) return;
    setStatusBusy(true);
    try {
      await api.updateUserStatus(user.id, statusModal);
      toast.success(`User status changed to ${statusModal}`);
      setStatusModal(null);
      reload();
    } catch (err) {
      toast.error('Failed to change status', requestIdOf(err));
    } finally {
      setStatusBusy(false);
    }
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title={loading ? 'Loading user…' : (user?.email ?? 'User')}
        description={user !== undefined ? `ID ${user.id}` : undefined}
        actions={
          <>
            <Button variant="secondary" onClick={() => navigate('/users')}>Back</Button>
            {canEdit && user != null ? (
              <>
                <Button variant="secondary" onClick={() => setCoinModal(true)}>
                  <Coins className="h-4 w-4" aria-hidden="true" />
                  Adjust Coins
                </Button>
                {user.status === 'ACTIVE' ? (
                  <Button variant="danger" onClick={() => setStatusModal('SUSPENDED')}>
                    <ShieldOff className="h-4 w-4" aria-hidden="true" />
                    Suspend
                  </Button>
                ) : (
                  <Button variant="secondary" onClick={() => setStatusModal('ACTIVE')}>
                    <ShieldCheck className="h-4 w-4" aria-hidden="true" />
                    Activate
                  </Button>
                )}
              </>
            ) : null}
          </>
        }
      />

      {loading ? (
        <div className="grid gap-6 lg:grid-cols-2">
          <Skeleton className="h-48" />
          <Skeleton className="h-48" />
        </div>
      ) : null}

      {user != null && data != null ? (
        <>
          <div className="grid gap-6 lg:grid-cols-2">
            <Card title="Account">
              <dl className="space-y-3 text-sm">
                <Row label="Email" value={user.email} />
                <Row label="Status" value={<UserStatusBadge status={user.status} />} />
                <Row label="Plan" value={<PlanBadge plan={user.plan} />} />
                <Row label="Coin Balance" value={<span className="tabular-nums">{user.coinBalance ?? '—'}</span>} />
                <Row label="Created" value={formatDateTime(user.createdAt)} />
                <Row label="Last Active" value={timeAgo(user.lastSeenAt)} />
              </dl>
            </Card>

            <Card title="Subscription">
              {data.subscription === null ? (
                <p className="text-sm text-ink2">No active subscription (FREE plan).</p>
              ) : (
                <dl className="space-y-3 text-sm">
                  <Row label="Product" value={data.subscription.productId} />
                  <Row label="Plan" value={<Badge tone="accent">{data.subscription.plan}</Badge>} />
                  <Row label="Status" value={<Badge tone="success">{data.subscription.status}</Badge>} />
                  <Row label="Expiry" value={formatDate(data.subscription.expiryDate)} />
                  <Row label="Last Verified" value={formatDateTime(data.subscription.lastVerifiedAt ?? data.subscription.lastVerified)} />
                </dl>
              )}
            </Card>
          </div>

          <Card title="Devices" description="Registered installations (max 5 per user).">
            <DeviceRows devices={data.devices} />
          </Card>

          <div className="grid gap-6 lg:grid-cols-2">
            <Card title="Security Events" description="Latest 10 events for this user.">
              <SecurityRows events={data.securityEvents} />
            </Card>
            <Card title="Support Tickets">
              <TicketRows tickets={data.supportTickets} />
            </Card>
          </div>
        </>
      ) : null}

      <ConfirmModal
        open={statusModal !== null}
        title={statusModal === 'SUSPENDED' ? 'Suspend user' : 'Activate user'}
        message={
          statusModal === 'SUSPENDED'
            ? 'The user will be blocked from signing in. Active local enforcement on their device is NOT terminated — account suspension must never remotely unlock or alter an active safety session.'
            : 'The user will be able to sign in again.'
        }
        confirmLabel={statusModal === 'SUSPENDED' ? 'Suspend user' : 'Activate user'}
        danger={statusModal === 'SUSPENDED'}
        loading={statusBusy}
        onConfirm={applyStatus}
        onCancel={() => setStatusModal(null)}
      />

      {user != null ? (
        <CoinAdjustModal
          open={coinModal}
          user={user}
          onClose={() => setCoinModal(false)}
          onDone={() => {
            setCoinModal(false);
            reload();
          }}
        />
      ) : null}
    </div>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }): JSX.Element {
  return (
    <div className="flex items-center justify-between gap-4">
      <dt className="text-ink2">{label}</dt>
      <dd className="font-medium text-ink">{value}</dd>
    </div>
  );
}

function requestIdOf(err: unknown): string | undefined {
  if (typeof err === 'object' && err !== null && 'requestId' in err) {
    const rid = (err as { requestId?: unknown }).requestId;
    return typeof rid === 'string' ? rid : undefined;
  }
  return undefined;
}

function DeviceRows({ devices }: { devices: Device[] }): JSX.Element {
  const columns: Array<TableColumn<Device>> = [
    { key: 'model', header: 'Device', render: (d) => <span className="font-medium">{d.model}</span> },
    { key: 'android', header: 'Android', render: (d) => <span className="text-ink2">{d.androidVersion}</span> },
    { key: 'app', header: 'App', render: (d) => <span className="text-ink2">{d.appVersion}</span> },
    { key: 'seen', header: 'Last Seen', render: (d) => <span className="text-ink2">{timeAgo(d.lastSeenAt)}</span> },
    { key: 'status', header: 'Status', render: (d) => <Badge tone={d.status === 'ACTIVE' ? 'success' : 'neutral'}>{d.status}</Badge> },
  ];
  return (
    <Table
      columns={columns}
      rows={devices}
      rowKey={(d) => d.id}
      emptyTitle="No devices"
      emptyMessage="This user has not registered a device yet."
    />
  );
}

function SecurityRows({ events }: { events: SecurityEvent[] }): JSX.Element {
  const columns: Array<TableColumn<SecurityEvent>> = [
    { key: 'time', header: 'Time', render: (e) => <span className="text-ink2">{formatDateTime(e.createdAt)}</span> },
    { key: 'type', header: 'Event', render: (e) => <span className="font-medium">{e.eventType ?? e.type}</span> },
    { key: 'severity', header: 'Severity', render: (e) => <SeverityBadge severity={e.severity} /> },
  ];
  return (
    <Table
      columns={columns}
      rows={events}
      rowKey={(e) => e.id}
      emptyTitle="No security events"
      emptyMessage="Clean record for this user."
    />
  );
}

function TicketRows({ tickets }: { tickets: Ticket[] }): JSX.Element {
  const columns: Array<TableColumn<Ticket>> = [
    { key: 'time', header: 'Created', render: (t) => <span className="text-ink2">{formatDate(t.createdAt)}</span> },
    { key: 'category', header: 'Category', render: (t) => <span className="font-medium">{t.category ?? t.subject ?? '—'}</span> },
    { key: 'status', header: 'Status', render: (t) => <Badge tone="info">{t.status}</Badge> },
  ];
  return (
    <Table
      columns={columns}
      rows={tickets}
      rowKey={(t) => t.id}
      emptyTitle="No tickets"
      emptyMessage="No support history for this user."
    />
  );
}

// ---------------------------------------------------------------------------
// Coin adjustment modal
// ---------------------------------------------------------------------------

function CoinAdjustModal({
  open,
  user,
  onClose,
  onDone,
}: {
  open: boolean;
  user: User;
  onClose: () => void;
  onDone: () => void;
}): JSX.Element {
  const toast = useToast();
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  const parsed = Number(amount);
  const valid = Number.isInteger(parsed) && parsed !== 0 && Math.abs(parsed) <= 10_000 && reason.trim().length >= 3;

  const submit = async (): Promise<void> => {
    if (!valid || busy) return;
    setBusy(true);
    try {
      const adjustment = await api.adjustCoins(user.id, parsed, reason.trim());
      toast.success(`Coins adjusted — new balance ${adjustment.balanceAfter}`);
      setAmount('');
      setReason('');
      onDone();
    } catch (err) {
      toast.error('Failed to adjust coins', requestIdOf(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Adjust Coins"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button onClick={submit} disabled={!valid || busy}>
            {busy ? 'Applying…' : 'Apply adjustment'}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <p className="text-sm text-ink2">
          Current balance: <span className="font-semibold text-ink tabular-nums">{user.coinBalance ?? '—'}</span> coins.
          Adjustments are recorded in the immutable ledger as ADMIN_ADJUSTMENT.
        </p>
        <Field label="Amount" htmlFor="coin-amount" hint="Non-zero integer, |amount| ≤ 10 000. Negative deducts.">
          <Input
            id="coin-amount"
            type="number"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder="e.g. 50 or -25"
          />
        </Field>
        <Field label="Reason" htmlFor="coin-reason" hint="Required — appears in the audit log.">
          <Textarea
            id="coin-reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            placeholder="Why is this adjustment being made?"
          />
        </Field>
      </div>
    </Modal>
  );
}
