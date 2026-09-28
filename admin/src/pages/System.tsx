/**
 * System: API health page (worker/D1/KV probes, environment info) and
 * Admin Users management (SUPER_ADMIN only).
 */
import { useState } from 'react';
import { Plus, Server, UserCog } from 'lucide-react';
import { api } from '../api/client';
import type { AdminRole } from '../api/types';
import { useApiData } from '../lib/hooks';
import { ROLE_LABELS, useAuth } from '../auth/AuthContext';
import {
  Badge,
  Button,
  Card,
  ErrorNotice,
  Field,
  HealthBadge,
  Input,
  Modal,
  PageHeader,
  Select,
  Skeleton,
  Table,
  useToast,
  type TableColumn,
} from '../components/ui';
import { formatDateTime, timeAgo } from '../lib/format';
import type { AdminUser } from '../api/types';

// ---------------------------------------------------------------------------
// API Health
// ---------------------------------------------------------------------------

export function SystemPage(): JSX.Element {
  const { data, loading, error, reload } = useApiData(() => api.getSystemHealth(), []);

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="API Health"
        description="Live probes executed by the Worker against its own dependencies."
        actions={
          <Button variant="secondary" onClick={reload}>
            <Server className="h-4 w-4" aria-hidden="true" />
            Re-probe
          </Button>
        }
      />

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Probes">
          {loading ? (
            <Skeleton className="h-40" />
          ) : (
            <dl className="space-y-3 text-sm">
              <Row label="Worker" value={<HealthBadge status={data?.worker ?? 'down'} />} />
              <Row label="D1 Latency" value={<span className="tabular-nums">{data?.d1LatencyMs ?? '—'} ms</span>} />
              <Row label="KV" value={<HealthBadge status={data?.kv ?? 'down'} />} />
            </dl>
          )}
        </Card>
        <Card title="Environments" description="Separate data per environment — production data is never used in development.">
          <ul className="space-y-3 text-sm">
            <li className="flex items-center justify-between rounded-lg border border-edge bg-page px-4 py-3">
              <span className="font-medium">Development</span>
              <Badge tone="info">wrangler dev · local D1</Badge>
            </li>
            <li className="flex items-center justify-between rounded-lg border border-edge bg-page px-4 py-3">
              <span className="font-medium">Staging</span>
              <Badge tone="warning">mld-api-staging</Badge>
            </li>
            <li className="flex items-center justify-between rounded-lg border border-edge bg-page px-4 py-3">
              <span className="font-medium">Production</span>
              <Badge tone="success">api.maxleveldetox.com</Badge>
            </li>
          </ul>
        </Card>
      </div>
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

// ---------------------------------------------------------------------------
// Admin users (SUPER_ADMIN only)
// ---------------------------------------------------------------------------

const ROLES: AdminRole[] = ['SUPER_ADMIN', 'ADMIN', 'SUPPORT', 'ANALYST', 'CONFIG_MANAGER', 'READ_ONLY'];

export function AdminUsersPage(): JSX.Element {
  const toast = useToast();
  const { admin: current } = useAuth();
  const { data, loading, error, reload } = useApiData(() => api.listAdminUsers(), []);

  const [modal, setModal] = useState(false);
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<AdminRole>('SUPPORT');
  const [busy, setBusy] = useState(false);

  const valid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) && password.length >= 12 && name.trim().length >= 2;

  const submit = async (): Promise<void> => {
    if (!valid || busy) return;
    setBusy(true);
    try {
      await api.createAdminUser({ email: email.trim(), name: name.trim(), password, role });
      toast.success(`Admin ${email} created with role ${role}`);
      setModal(false);
      setEmail('');
      setName('');
      setPassword('');
      setRole('SUPPORT');
      reload();
    } catch (err) {
      toast.error('Failed to create admin', rid(err));
    } finally {
      setBusy(false);
    }
  };

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  const columns: Array<TableColumn<AdminUser>> = [
    { key: 'email', header: 'Email', render: (a) => <span className="font-medium">{a.email}</span> },
    { key: 'name', header: 'Name', render: (a) => <span className="text-ink2">{a.name ?? '—'}</span> },
    { key: 'role', header: 'Role', render: (a) => <Badge tone={a.role === 'SUPER_ADMIN' ? 'danger' : 'accent'}>{ROLE_LABELS[a.role]}</Badge> },
    { key: 'created', header: 'Created', render: (a) => <span className="text-ink2">{formatDateTime(a.createdAt)}</span> },
    { key: 'login', header: 'Last Login', render: (a) => <span className="text-ink2">{timeAgo(a.lastLoginAt)}</span> },
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        title="Admin Users"
        description="Panel accounts are separate from app users. Role changes take effect on the very next request."
        actions={
          <Button onClick={() => setModal(true)}>
            <Plus className="h-4 w-4" aria-hidden="true" />
            New Admin
          </Button>
        }
      />

      <Card padded={false}>
        <Table
          columns={columns}
          rows={data ?? []}
          rowKey={(a) => a.id}
          loading={loading}
          emptyTitle="No admin users"
          emptyMessage="Create the first admin account."
        />
      </Card>

      <Modal
        open={modal}
        title="Create Admin User"
        onClose={() => setModal(false)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setModal(false)}>Cancel</Button>
            <Button onClick={submit} disabled={!valid || busy}>
              <UserCog className="h-4 w-4" aria-hidden="true" />
              {busy ? 'Creating…' : 'Create admin'}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Email" htmlFor="adm-email">
            <Input id="adm-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="ops@maxleveldetox.com" />
          </Field>
          <Field label="Name" htmlFor="adm-name">
            <Input id="adm-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Jane Ops" />
          </Field>
          <Field label="Password" htmlFor="adm-password" hint="Minimum 12 characters. Stored as PBKDF2-SHA256 (100k iterations).">
            <Input id="adm-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
          </Field>
          <Field label="Role" htmlFor="adm-role" hint={current !== null && current.role === 'SUPER_ADMIN' ? undefined : 'Only SUPER_ADMIN can create admins.'}>
            <Select id="adm-role" value={role} onChange={(e) => setRole(e.target.value as AdminRole)}>
              {ROLES.map((r) => (
                <option key={r} value={r}>{ROLE_LABELS[r]}</option>
              ))}
            </Select>
          </Field>
        </div>
      </Modal>
    </div>
  );
}

function rid(err: unknown): string | undefined {
  if (typeof err === 'object' && err !== null && 'requestId' in err) {
    const r = (err as { requestId?: unknown }).requestId;
    return typeof r === 'string' ? r : undefined;
  }
  return undefined;
}
