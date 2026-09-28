/**
 * Feature Flags: enable/disable, staged rollout (0/1/5/10/25/50/100%),
 * minimum version gating. Every change is audited server-side.
 */
import { useState } from 'react';
import { api } from '../api/client';
import type { FeatureFlag, FlagPatch } from '../api/types';
import { useApiData } from '../lib/hooks';
import { Badge, Card, ConfirmModal, ErrorNotice, Field, Input, PageHeader, Skeleton, Toggle, useToast } from '../components/ui';

const ROLLOUT_STEPS = [0, 1, 5, 10, 25, 50, 100] as const;

/** A pending change: the flag being edited plus ONLY the fields being changed. */
interface FlagPending {
  flag: FeatureFlag;
  patch: FlagPatch;
}

export default function FeatureFlagsPage(): JSX.Element {
  const toast = useToast();
  const { data, loading, error, reload } = useApiData(() => api.listFlags(), []);
  const [pending, setPending] = useState<FlagPending | null>(null);
  const [busy, setBusy] = useState(false);

  const apply = async (patch: FlagPatch): Promise<void> => {
    if (pending === null || busy) return;
    setBusy(true);
    try {
      await api.updateFlag(pending.flag.key, patch);
      toast.success(`Flag ${pending.flag.key} updated`);
      setPending(null);
      reload();
    } catch (err) {
      toast.error('Failed to update flag', rid(err));
    } finally {
      setBusy(false);
    }
  };

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  const flags = data ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="Feature Flags"
        description="Controlled rollout with snap-point percentages. Core enforcement never depends on a remote flag being reachable — flags gate product features, not safety."
      />

      {loading ? (
        <div className="grid gap-4 md:grid-cols-2">
          <Skeleton className="h-40" />
          <Skeleton className="h-40" />
          <Skeleton className="h-40" />
          <Skeleton className="h-40" />
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {flags.map((flag) => (
            <Card key={flag.key}>
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="font-mono text-sm font-semibold">{flag.key}</p>
                    <Badge tone={flag.enabled ? 'success' : 'neutral'}>{flag.enabled ? 'ON' : 'OFF'}</Badge>
                    <Badge tone={flag.rolloutPercentage === 100 ? 'info' : 'warning'}>{flag.rolloutPercentage}% rollout</Badge>
                  </div>
                  <p className="mt-1.5 text-xs text-ink2">{flag.description}</p>
                </div>
                <Toggle
                  checked={flag.enabled}
                  onChange={() => setPending({ flag, patch: { enabled: !flag.enabled } })}
                  label={`Toggle ${flag.key}`}
                  disabled={busy}
                />
              </div>

              <div className="mt-4">
                <p className="mb-2 text-[10px] font-bold uppercase tracking-widest text-ink2">Rollout</p>
                <div className="flex flex-wrap gap-1.5" role="group" aria-label={`Rollout percentage for ${flag.key}`}>
                  {ROLLOUT_STEPS.map((step) => (
                    <button
                      key={step}
                      type="button"
                      onClick={() => setPending({ flag, patch: { rolloutPercentage: step } })}
                      className={
                        'rounded-md border px-2.5 py-1 text-xs font-semibold transition-colors ' +
                        (flag.rolloutPercentage === step
                          ? 'border-accent bg-accent/15 text-accent'
                          : 'border-edge text-ink2 hover:border-accent/50 hover:text-ink')
                      }
                    >
                      {step}%
                    </button>
                  ))}
                </div>
              </div>

              <div className="mt-4">
                <Field label="Minimum version" htmlFor={`flag-minver-${flag.key}`} hint="Only applies to app versions >= this value.">
                  <Input
                    id={`flag-minver-${flag.key}`}
                    defaultValue={flag.minimumVersion}
                    onBlur={(e) => {
                      if (e.target.value.trim() !== flag.minimumVersion) {
                        setPending({ flag, patch: { minimumVersion: e.target.value.trim() } });
                      }
                    }}
                    placeholder="1.0.0"
                  />
                </Field>
              </div>
            </Card>
          ))}
        </div>
      )}

      <ConfirmModal
        open={pending !== null}
        title={`Change flag ${pending?.flag.key ?? ''}`}
        message={
          pending !== null ? (
            <>
              {describePatch(pending.patch)}
              <span className="mt-2 block text-xs text-ink2">This action is recorded in the audit log.</span>
            </>
          ) : (
            ''
          )
        }
        confirmLabel="Apply change"
        loading={busy}
        onConfirm={() => {
          if (pending === null) return;
          void apply(pending.patch);
        }}
        onCancel={() => setPending(null)}
      />
    </div>
  );
}

/** Human-readable intent for the confirm modal — only what is actually changing. */
function describePatch(patch: FlagPatch): JSX.Element {
  if (patch.enabled !== undefined) {
    return (
      <>
        Set <strong>{patch.enabled ? 'ON' : 'OFF'}</strong>?
      </>
    );
  }
  if (patch.rolloutPercentage !== undefined) {
    return (
      <>
        Set rollout to <strong>{patch.rolloutPercentage}%</strong>?
      </>
    );
  }
  return (
    <>
      Set minimum version to <strong>{patch.minimumVersion || '1.0.0'}</strong>?
    </>
  );
}

function rid(err: unknown): string | undefined {
  if (typeof err === 'object' && err !== null && 'requestId' in err) {
    const r = (err as { requestId?: unknown }).requestId;
    return typeof r === 'string' ? r : undefined;
  }
  return undefined;
}
