/**
 * App Versions: minimum/recommended/latest + force update policy.
 * Force-update messages are displayed by the app without breaking an
 * active enforcement session.
 */
import { useEffect, useMemo, useState } from 'react';
import { Package } from 'lucide-react';
import { api } from '../api/client';
import type { AppVersionPolicy } from '../api/types';
import { useApiData } from '../lib/hooks';
import { formatDateTime } from '../lib/format';
import {
  Badge,
  Button,
  Card,
  ConfirmModal,
  ErrorNotice,
  Field,
  Input,
  PageHeader,
  Skeleton,
  Textarea,
  Toggle,
  useToast,
} from '../components/ui';

export default function AppVersionsPage(): JSX.Element {
  const toast = useToast();
  const { data, loading, error, reload } = useApiData(() => api.getAppVersionPolicy(), []);

  const [minimum, setMinimum] = useState('');
  const [latest, setLatest] = useState('');
  const [forceUpdate, setForceUpdate] = useState(false);
  const [message, setMessage] = useState('');
  const [modal, setModal] = useState(false);
  const [busy, setBusy] = useState(false);
  const [initialized, setInitialized] = useState(false);

  // The SPA's frozen contract returns a flat policy object; the worker wraps
  // the (single) policy row in `{ versions: [...] }` — accept both shapes.
  const policy = useMemo<AppVersionPolicy | null>(() => {
    if (data === null) return null;
    const possible = data as AppVersionPolicy & { versions?: AppVersionPolicy[] };
    return Array.isArray(possible.versions) ? possible.versions[0] ?? null : possible;
  }, [data]);

  useEffect(() => {
    if (policy !== null && !initialized) {
      setMinimum(policy.minimum);
      setLatest(policy.latest);
      setForceUpdate(policy.forceUpdate);
      setMessage(policy.message);
      setInitialized(true);
    }
  }, [policy, initialized]);

  const semverOk = /^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/.test(minimum) && /^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/.test(latest);

  const submit = async (): Promise<void> => {
    if (!semverOk || busy) return;
    setBusy(true);
    try {
      await api.updateAppVersionPolicy({ minimum, latest, forceUpdate, message });
      toast.success('Version policy updated');
      setModal(false);
      reload();
    } catch (err) {
      toast.error('Failed to update version policy', rid(err));
    } finally {
      setBusy(false);
    }
  };

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="App Versions"
        description="Version gating policy served to clients. A critical update message never breaks an active enforcement session."
      />

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Current Policy" description="What clients receive from GET /app/version.">
          {loading || policy === null ? (
            <Skeleton className="h-48" />
          ) : (
            <dl className="space-y-3 text-sm">
              <Row label="Minimum supported" value={<span className="font-mono">{policy.minimum}</span>} />
              <Row label="Latest" value={<span className="font-mono">{policy.latest}</span>} />
              <Row label="Force update" value={<Badge tone={policy.forceUpdate ? 'danger' : 'neutral'}>{policy.forceUpdate ? 'ON' : 'OFF'}</Badge>} />
              <Row label="Message" value={policy.message.length > 0 ? policy.message : <span className="text-ink2">—</span>} />
              {policy.updatedAt !== undefined ? <Row label="Updated" value={formatDateTime(policy.updatedAt)} /> : null}
            </dl>
          )}
        </Card>

        <Card title="Update Policy">
          <div className="space-y-5">
            <Field label="Minimum supported version" htmlFor="ver-min" hint="Versions below this are asked to update.">
              <Input id="ver-min" value={minimum} onChange={(e) => setMinimum(e.target.value)} placeholder="1.0.0" />
            </Field>
            <Field label="Latest version" htmlFor="ver-latest" hint="Shown as the recommended version.">
              <Input id="ver-latest" value={latest} onChange={(e) => setLatest(e.target.value)} placeholder="1.1.0" />
            </Field>
            <div className="flex items-center justify-between rounded-lg border border-edge bg-page p-4">
              <div>
                <p className="text-sm font-semibold">Force update</p>
                <p className="text-xs text-ink2">For security-critical releases only. Use with care.</p>
              </div>
              <Toggle checked={forceUpdate} onChange={setForceUpdate} label="Toggle force update" />
            </div>
            <Field label="Update message" htmlFor="ver-msg" hint="Optional. Displayed in the update prompt.">
              <Textarea id="ver-msg" rows={3} value={message} onChange={(e) => setMessage(e.target.value)} placeholder="This update fixes important enforcement issues." />
            </Field>
            <Button onClick={() => setModal(true)} disabled={!semverOk}>
              <Package className="h-4 w-4" aria-hidden="true" />
              Save Version Policy
            </Button>
            {!semverOk ? <p className="text-xs text-bad">Both versions must be valid semver (e.g. 1.0.0).</p> : null}
          </div>
        </Card>
      </div>

      <ConfirmModal
        open={modal}
        title="Publish version policy"
        message={
          <>
            Minimum <strong>{minimum}</strong>, latest <strong>{latest}</strong>, force update{' '}
            <strong>{forceUpdate ? 'ON' : 'OFF'}</strong>.
            <span className="mt-2 block text-xs text-ink2">This action is recorded in the audit log.</span>
          </>
        }
        confirmLabel="Publish policy"
        requireText="PUBLISH"
        loading={busy}
        onConfirm={submit}
        onCancel={() => setModal(false)}
      />
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

function rid(err: unknown): string | undefined {
  if (typeof err === 'object' && err !== null && 'requestId' in err) {
    const r = (err as { requestId?: unknown }).requestId;
    return typeof r === 'string' ? r : undefined;
  }
  return undefined;
}
