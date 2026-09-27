/**
 * Plans (v2.2 Phase D): subscription plan catalog editor.
 * Prices are integers in the smallest currency unit (paisa for BDT) and are
 * validated on both sides against the frozen bounds.
 */
import { useMemo, useState } from 'react';
import { BadgeCheck, Package, Pencil, Wallet } from 'lucide-react';
import { api, toApiError } from '../api/client';
import type { Plan } from '../api/types';
import { useApiData } from '../lib/hooks';
import {
  Badge,
  Button,
  Card,
  ErrorNotice,
  Field,
  Input,
  Modal,
  PageHeader,
  Skeleton,
  StatCard,
  Table,
  Toggle,
  type TableColumn,
  useToast,
} from '../components/ui';
import { formatNumber } from '../lib/format';

function formatBdt(minor: number): string {
  return `৳${(minor / 100).toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`;
}

interface EditState {
  plan: Plan;
  displayName: string;
  description: string;
  priceTaka: string;
  durationDays: string;
  isActive: boolean;
  isPopular: boolean;
  sortOrder: string;
}

export default function PlansPage(): JSX.Element {
  const toast = useToast();
  const { data, loading, error, reload } = useApiData<Plan[]>(api.listPlans, []);
  const [edit, setEdit] = useState<EditState | null>(null);
  const [saving, setSaving] = useState(false);

  const plans = data ?? [];
  const playCount = plans.filter((p) => p.source === 'play' && p.isActive).length;
  const bkashCount = plans.filter((p) => p.source === 'bkash' && p.isActive).length;
  const popular = plans.find((p) => p.isPopular) ?? null;

  const openEdit = (plan: Plan): void => {
    setEdit({
      plan,
      displayName: plan.displayName,
      description: plan.description ?? '',
      priceTaka: String(plan.priceMinor / 100),
      durationDays: String(plan.durationDays),
      isActive: plan.isActive,
      isPopular: plan.isPopular,
      sortOrder: String(plan.sortOrder),
    });
  };

  const save = async (): Promise<void> => {
    if (edit === null) return;
    const priceTaka = Number(edit.priceTaka);
    const durationDays = Number(edit.durationDays);
    const sortOrder = Number(edit.sortOrder);
    if (edit.priceTaka.trim() === '' || !Number.isFinite(priceTaka) || priceTaka < 0 || priceTaka > 1_000_000) {
      toast.error('Invalid price — must be a number between 0 and 1,000,000 BDT.');
      return;
    }
    if (!Number.isInteger(durationDays) || durationDays < 1 || durationDays > 400) {
      toast.error('Invalid duration — must be an integer between 1 and 400 days.');
      return;
    }
    if (!Number.isInteger(sortOrder) || sortOrder < 0 || sortOrder > 999) {
      toast.error('Invalid sort order — must be an integer between 0 and 999.');
      return;
    }
    setSaving(true);
    try {
      await api.updatePlan(edit.plan.id, {
        displayName: edit.displayName.trim().length > 0 ? edit.displayName.trim() : edit.plan.displayName,
        description: edit.description.trim().length > 0 ? edit.description.trim() : null,
        priceMinor: Math.round(priceTaka * 100),
        durationDays,
        isActive: edit.isActive,
        isPopular: edit.isPopular,
        sortOrder,
      });
      toast.success('Plan updated');
      setEdit(null);
      await reload();
    } catch (err) {
      const apiErr = toApiError(err);
      toast.error(`Failed to update plan: ${apiErr.message}`, apiErr.requestId);
    } finally {
      setSaving(false);
    }
  };

  const columns: Array<TableColumn<Plan>> = useMemo(
    () => [
      {
        key: 'name',
        header: 'Plan',
        render: (p) => (
          <div className="flex flex-col">
            <span className="font-medium text-ink">{p.displayName}</span>
            <span className="font-mono text-xs text-ink2">{p.productId}</span>
          </div>
        ),
      },
      { key: 'kind', header: 'Kind', render: (p) => <Badge tone="accent">{p.plan}</Badge> },
      {
        key: 'source',
        header: 'Source',
        render: (p) => (
          <Badge tone={p.source === 'play' ? 'info' : 'neutral'}>{p.source === 'play' ? 'Google Play' : 'bKash'}</Badge>
        ),
      },
      { key: 'price', header: 'Price', align: 'right', render: (p) => <span className="tabular-nums">{formatBdt(p.priceMinor)}</span> },
      { key: 'days', header: 'Duration', align: 'right', render: (p) => <span className="tabular-nums text-ink2">{formatNumber(p.durationDays)} days</span> },
      {
        key: 'flags',
        header: 'Flags',
        render: (p) => (
          <div className="flex gap-1">
            {p.isPopular ? <Badge tone="warning">popular</Badge> : null}
            {!p.isActive ? <Badge tone="danger">hidden</Badge> : null}
          </div>
        ),
      },
      {
        key: 'actions',
        header: '',
        align: 'right',
        render: (p) => (
          <Button variant="secondary" size="sm" onClick={() => openEdit(p)}>
            <Pencil className="mr-1 h-3.5 w-3.5" aria-hidden="true" /> Edit
          </Button>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="Plans"
        description="Server-driven subscription catalog served to the app. Prices are in the smallest currency unit and validated on both sides."
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Active Play plans" value={formatNumber(playCount)} icon={<Package className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Active bKash plans" value={formatNumber(bkashCount)} icon={<Wallet className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Highlighted tier" value={popular !== null ? popular.displayName : '—'} icon={<BadgeCheck className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="Total plans" value={formatNumber(plans.length)} icon={<Package className="h-4 w-4" aria-hidden="true" />} loading={loading} />
      </div>

      {error !== null ? <ErrorNotice error={error} onRetry={reload} /> : null}

      {loading ? (
        <Card>
          <div className="space-y-3 p-4">
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-2/3" />
          </div>
        </Card>
      ) : (
        <Card padded={false}>
          <Table
            columns={columns}
            rows={plans}
            rowKey={(p) => p.id}
            loading={loading}
            emptyTitle="No plans"
            emptyMessage="The plan catalog is empty — seed data is created by migration 002."
          />
        </Card>
      )}

      <Modal
        open={edit !== null}
        title={edit !== null ? `Edit ${edit.plan.displayName}` : ''}
        onClose={() => setEdit(null)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setEdit(null)} disabled={saving}>
              Cancel
            </Button>
            <Button onClick={() => void save()} loading={saving}>
              Save plan
            </Button>
          </>
        }
      >
        {edit !== null ? (
          <div className="space-y-4">
            <Field label="Display name" htmlFor="plan-name">
              <Input
                id="plan-name"
                value={edit.displayName}
                maxLength={64}
                onChange={(e) => setEdit({ ...edit, displayName: e.target.value })}
              />
            </Field>
            <Field label="Description" htmlFor="plan-desc" hint="Shown on the paywall card.">
              <Input
                id="plan-desc"
                value={edit.description}
                maxLength={300}
                onChange={(e) => setEdit({ ...edit, description: e.target.value })}
              />
            </Field>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Price (BDT)" htmlFor="plan-price" hint={`Current: ${formatBdt(edit.plan.priceMinor)}`}>
                <Input
                  id="plan-price"
                  inputMode="decimal"
                  value={edit.priceTaka}
                  onChange={(e) => setEdit({ ...edit, priceTaka: e.target.value })}
                />
              </Field>
              <Field label="Duration (days)" htmlFor="plan-days" hint="1–400">
                <Input
                  id="plan-days"
                  inputMode="numeric"
                  value={edit.durationDays}
                  onChange={(e) => setEdit({ ...edit, durationDays: e.target.value })}
                />
              </Field>
            </div>
            <Field label="Sort order" htmlFor="plan-sort" hint="Lower sorts first on the paywall.">
              <Input
                id="plan-sort"
                inputMode="numeric"
                value={edit.sortOrder}
                onChange={(e) => setEdit({ ...edit, sortOrder: e.target.value })}
              />
            </Field>
            <div className="flex flex-wrap gap-6 pt-2">
              <Toggle
                checked={edit.isActive}
                onChange={(v) => setEdit({ ...edit, isActive: v })}
                label="Visible in app"
              />
              <Toggle
                checked={edit.isPopular}
                onChange={(v) => setEdit({ ...edit, isPopular: v })}
                label="Highlight as best value"
              />
            </div>
            <p className="rounded-lg bg-surface2 px-3 py-2 text-xs text-ink2">
              Product id <span className="font-mono">{edit.plan.productId}</span> is fixed — the Play SKU binding
              never changes after creation.
            </p>
          </div>
        ) : null}
      </Modal>
    </div>
  );
}
