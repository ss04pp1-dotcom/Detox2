/**
 * Remote Config — THE key product control page.
 *
 * Workflow (frozen contract):
 *   edit draft -> Save Draft (PUT) -> Review Changes (diff) -> Publish
 *   (type-to-confirm) -> optionally Rollback to any earlier version.
 *
 * Bounds are validated inline (frozen CONFIG_BOUNDS) AND clamped server-side.
 * A config can never unlock an active device session — it only controls
 * bounded product behavior.
 */
import { useEffect, useMemo, useState } from 'react';
import { History, Upload, Save, Undo2 } from 'lucide-react';
import { api } from '../api/client';
import type { AppConfig, ConfigDoc, NumericConfigKey, MultiplierConfigKey, StringConfigKey } from '../api/types';
import { CONFIG_MULTIPLIER_BOUNDS, CONFIG_NUMERIC_BOUNDS, CONFIG_STRING_BOUNDS } from '../api/types';
import { useApiData } from '../lib/hooks';
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
  Skeleton,
  Textarea,
  Toggle,
  useToast,
} from '../components/ui';
import { formatDateTime } from '../lib/format';

const NUMERIC_FIELDS: Array<{ key: NumericConfigKey; label: string; hint: string }> = [
  { key: 'shortsWarningCount', label: 'Shorts warnings before Cage', hint: '3 – 7 warnings' },
  { key: 'cageDurationSeconds', label: 'Cage duration (seconds)', hint: '300 – 7200 (5 min – 2 h)' },
  { key: 'tempUnlockCoins', label: 'Temporary Unlock cost (coins)', hint: '1 – 50 coins' },
  { key: 'tempUnlockMinutes', label: 'Temporary Unlock duration (minutes)', hint: '1 – 15 minutes' },
  { key: 'bailoutCoins', label: 'Bailout penalty (coins)', hint: '50 – 5000 coins' },
  { key: 'defaultStudyMinutes', label: 'Default Study duration (minutes)', hint: '10 – 480 minutes' },
  { key: 'defaultDetoxMinutes', label: 'Default Detox duration (minutes)', hint: '30 – 1440 minutes' },
  { key: 'dpDailyCap', label: 'DP daily cap', hint: '50 – 1000 (progress layer pacing)' },
  { key: 'dpWeeklyCap', label: 'DP weekly cap', hint: '200 – 5000' },
  { key: 'dpMonthlyCap', label: 'DP monthly cap', hint: '1000 – 20000' },
  // v2.2 Phase D — growth & monetization.
  { key: 'trialDays', label: 'Free trial length (days)', hint: '3 – 14 days, one per device' },
  { key: 'breakPassesPerWeek', label: 'Break passes per week', hint: '0 – 5 weekly 5-min passes' },
  { key: 'insightNudgeHour', label: 'Daily insight hour (24h)', hint: '17 – 22 (local device time)' },
];

const STRING_FIELDS: Array<{ key: StringConfigKey; label: string; hint: string; multiline?: boolean }> = [
  { key: 'bkashNumber', label: 'bKash merchant number', hint: 'Shown on the payment screen. Empty (with the toggle off) disables the gateway.' },
  { key: 'bkashInstructions', label: 'bKash payment instructions', hint: 'Max 2000 characters — shown step by step in the app.', multiline: true },
];

const MULTIPLIER_FIELDS: Array<{ key: MultiplierConfigKey; label: string; hint: string }> = [
  { key: 'dpMultiplierFocus', label: 'DP multiplier — Focus', hint: '0.5 – 3.0 (sessions, monk, prime)' },
  { key: 'dpMultiplierReels', label: 'DP multiplier — Reels', hint: '0.5 – 3.0 (intercepted reels)' },
  { key: 'dpMultiplierNeutral', label: 'DP multiplier — Neutral', hint: '0.5 – 3.0 (clean days, check-ins…)' },
];

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export default function RemoteConfigPage(): JSX.Element {
  const toast = useToast();
  const { data, loading, error, reload } = useApiData(() => api.getConfig(), []);

  const [draft, setDraft] = useState<AppConfig | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [publishModal, setPublishModal] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [diffModal, setDiffModal] = useState(false);
  const [rollbackVersion, setRollbackVersion] = useState<number | null>(null);
  const [rollingBack, setRollingBack] = useState(false);
  // Raw (unclamped) text currently held by a focused numeric/multiplier input.
  // Values are clamped and the raw override cleared on blur (m4).
  const [rawInputs, setRawInputs] = useState<Partial<Record<NumericConfigKey | MultiplierConfigKey, string>>>({});

  useEffect(() => {
    if (data !== null && draft === null) {
      // The worker returns draft: null after every publish (and before the
      // first draft) — fall back to the published values so the editor still
      // renders; editing + Save Draft then creates the new draft.
      const source: ConfigDoc = data.draft ?? data.published;
      const { _version, ...rest } = source;
      void _version;
      // Normalize Phase C fields for configs created before v2.1 (mock +
      // legacy published docs) so the editor never binds undefined values.
      const normalized: AppConfig = {
        ...rest,
        gamificationEnabled: rest.gamificationEnabled ?? true,
        dpMultiplierFocus: rest.dpMultiplierFocus ?? 1.0,
        dpMultiplierReels: rest.dpMultiplierReels ?? 1.0,
        dpMultiplierNeutral: rest.dpMultiplierNeutral ?? 1.0,
        dpDailyCap: rest.dpDailyCap ?? 150,
        dpWeeklyCap: rest.dpWeeklyCap ?? 800,
        dpMonthlyCap: rest.dpMonthlyCap ?? 3000,
        // v2.2 Phase D normalization for pre-v2.2 configs.
        paymentsEnabled: rest.paymentsEnabled ?? true,
        bkashNumber: rest.bkashNumber ?? '',
        bkashInstructions: rest.bkashInstructions ?? '',
        trialDays: rest.trialDays ?? 7,
        breakPassesPerWeek: rest.breakPassesPerWeek ?? 2,
        insightNudgeEnabled: rest.insightNudgeEnabled ?? true,
        insightNudgeHour: rest.insightNudgeHour ?? 20,
      };
      setDraft(normalized);
      setDirty(false);
    }
  }, [data, draft]);

  const published = data?.published ?? null;

  const clearRaw = (key: NumericConfigKey | MultiplierConfigKey): void => {
    setRawInputs((prev) => {
      if (prev[key] === undefined) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  const setField = (key: NumericConfigKey, raw: string): void => {
    if (!draft) return;
    const parsed = Number(raw);
    setRawInputs((prev) => ({ ...prev, [key]: raw }));
    // Store the raw (unclamped) number while typing; clamping happens on blur.
    setDraft({ ...draft, [key]: Number.isFinite(parsed) ? parsed : draft[key] });
    setDirty(true);
  };

  const commitField = (key: NumericConfigKey): void => {
    if (!draft) return;
    const bounds = CONFIG_NUMERIC_BOUNDS[key];
    const value = Number.isFinite(draft[key]) ? draft[key] : bounds.min;
    setDraft({ ...draft, [key]: clamp(Math.round(value), bounds.min, bounds.max) });
    clearRaw(key);
  };

  const setMultiplier = (key: MultiplierConfigKey, raw: string): void => {
    if (!draft) return;
    const parsed = Number(raw);
    setRawInputs((prev) => ({ ...prev, [key]: raw }));
    // Store the raw (unclamped) number while typing; clamping happens on blur.
    setDraft({ ...draft, [key]: Number.isFinite(parsed) ? parsed : draft[key] });
    setDirty(true);
  };

  const commitMultiplier = (key: MultiplierConfigKey): void => {
    if (!draft) return;
    const bounds = CONFIG_MULTIPLIER_BOUNDS[key];
    const value = Number.isFinite(draft[key]) ? draft[key] : bounds.min;
    setDraft({ ...draft, [key]: Math.round(clamp(value, bounds.min, bounds.max) * 100) / 100 });
    clearRaw(key);
  };

  const errors = useMemo(() => {
    if (draft === null) return [];
    const list: string[] = [];
    for (const f of NUMERIC_FIELDS) {
      const b = CONFIG_NUMERIC_BOUNDS[f.key];
      const v = draft[f.key];
      if (!Number.isInteger(v) || v < b.min || v > b.max) {
        list.push(`${f.label} must be an integer between ${b.min} and ${b.max}`);
      }
    }
    for (const f of MULTIPLIER_FIELDS) {
      const b = CONFIG_MULTIPLIER_BOUNDS[f.key];
      const v = draft[f.key];
      if (!Number.isFinite(v) || v < b.min || v > b.max) {
        list.push(`${f.label} must be between ${b.min} and ${b.max}`);
      }
    }
    if (!/^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$/.test(draft.minSupportedVersion)) {
      list.push('Minimum supported version must be semver (e.g. 1.0.0)');
    }
    return list;
  }, [draft]);

  const changedFields = useMemo(() => {
    if (draft === null || published === null) return [] as Array<{ label: string; from: string; to: string }>;
    const fields: Array<{ label: string; from: string; to: string }> = [];
    for (const f of NUMERIC_FIELDS) {
      if (draft[f.key] !== published[f.key]) {
        fields.push({ label: f.label, from: String(published[f.key]), to: String(draft[f.key]) });
      }
    }
    for (const f of MULTIPLIER_FIELDS) {
      if (draft[f.key] !== published[f.key]) {
        fields.push({ label: f.label, from: String(published[f.key]), to: String(draft[f.key]) });
      }
    }
    if (draft.minSupportedVersion !== published.minSupportedVersion) {
      fields.push({ label: 'Minimum supported version', from: published.minSupportedVersion, to: draft.minSupportedVersion });
    }
    if (draft.maintenanceMode !== published.maintenanceMode) {
      fields.push({
        label: 'Maintenance mode',
        from: published.maintenanceMode ? 'ON' : 'OFF',
        to: draft.maintenanceMode ? 'ON' : 'OFF',
      });
    }
    if (draft.gamificationEnabled !== published.gamificationEnabled) {
      fields.push({
        label: 'Progress layer (gamification)',
        from: published.gamificationEnabled ? 'ON' : 'OFF',
        to: draft.gamificationEnabled ? 'ON' : 'OFF',
      });
    }
    // v2.2 Phase D diffs.
    if (draft.paymentsEnabled !== published.paymentsEnabled) {
      fields.push({
        label: 'Payments (global kill-switch)',
        from: published.paymentsEnabled ? 'ON' : 'OFF',
        to: draft.paymentsEnabled ? 'ON' : 'OFF',
      });
    }
    if (draft.insightNudgeEnabled !== published.insightNudgeEnabled) {
      fields.push({
        label: 'Daily insight nudge',
        from: published.insightNudgeEnabled ? 'ON' : 'OFF',
        to: draft.insightNudgeEnabled ? 'ON' : 'OFF',
      });
    }
    for (const f of STRING_FIELDS) {
      if (draft[f.key] !== published[f.key]) {
        fields.push({
          label: f.label,
          from: published[f.key] === '' ? '(empty)' : published[f.key],
          to: draft[f.key] === '' ? '(empty)' : draft[f.key],
        });
      }
    }
    return fields;
  }, [draft, published]);

  const saveDraft = async (): Promise<void> => {
    if (draft === null || errors.length > 0 || saving) return;
    setSaving(true);
    try {
      await api.saveConfigDraft(draft);
      toast.success('Draft saved');
      setDirty(false);
      reload();
    } catch (err) {
      toast.error('Failed to save draft', rid(err));
    } finally {
      setSaving(false);
    }
  };

  const publish = async (): Promise<void> => {
    if (publishing) return;
    setPublishing(true);
    try {
      await api.publishConfig();
      toast.success('Configuration published');
      setPublishModal(false);
      reload();
    } catch (err) {
      toast.error('Failed to publish', rid(err));
    } finally {
      setPublishing(false);
    }
  };

  const rollback = async (): Promise<void> => {
    if (rollbackVersion === null || rollingBack) return;
    setRollingBack(true);
    try {
      await api.rollbackConfig(rollbackVersion);
      toast.success(`Rolled back to version ${rollbackVersion} (re-published as a new version)`);
      setRollbackVersion(null);
      reload();
    } catch (err) {
      toast.error('Failed to roll back', rid(err));
    } finally {
      setRollingBack(false);
    }
  };

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Remote Config"
        description="Bounded product values. Remote config can never unlock an active session, grant unlimited coins or disable emergency access — those boundaries are frozen."
        actions={
          <>
            <Button variant="secondary" onClick={() => setDiffModal(true)} disabled={published === null}>
              <History className="h-4 w-4" aria-hidden="true" />
              Review Changes
            </Button>
            <Button onClick={saveDraft} disabled={draft === null || errors.length > 0 || saving || !dirty}>
              <Save className="h-4 w-4" aria-hidden="true" />
              {saving ? 'Saving…' : 'Save Draft'}
            </Button>
            <Button variant="danger" onClick={() => setPublishModal(true)} disabled={draft === null || errors.length > 0 || dirty}>
              <Upload className="h-4 w-4" aria-hidden="true" />
              Publish
            </Button>
          </>
        }
      />

      {dirty ? (
        <p className="rounded-lg border border-warn/40 bg-warn/10 px-4 py-3 text-sm text-warn">
          You have unsaved draft changes. Save the draft before publishing.
        </p>
      ) : null}
      {errors.length > 0 ? (
        <ul className="space-y-1 rounded-lg border border-bad/40 bg-bad/10 px-4 py-3 text-sm text-bad">
          {errors.map((e) => (
            <li key={e}>{e}</li>
          ))}
        </ul>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Draft" description={data?.draft !== null ? `Editing draft ${_versionOf(data?.draft)}` : 'New draft'}>
          {loading || draft === null ? (
            <Skeleton className="h-96" />
          ) : (
            <div className="space-y-5">
              {NUMERIC_FIELDS.map((f) => (
                <Field key={f.key} label={f.label} htmlFor={`cfg-${f.key}`} hint={f.hint}>
                  <Input
                    id={`cfg-${f.key}`}
                    type="number"
                    min={CONFIG_NUMERIC_BOUNDS[f.key].min}
                    max={CONFIG_NUMERIC_BOUNDS[f.key].max}
                    step={1}
                    value={rawInputs[f.key] ?? draft[f.key]}
                    onChange={(e) => setField(f.key, e.target.value)}
                    onBlur={() => commitField(f.key)}
                  />
                </Field>
              ))}
              <Field label="Minimum supported version" htmlFor="cfg-minver" hint="Below this version the app prompts for update.">
                <Input
                  id="cfg-minver"
                  value={draft.minSupportedVersion}
                  onChange={(e) => {
                    setDraft({ ...draft, minSupportedVersion: e.target.value });
                    setDirty(true);
                  }}
                  placeholder="1.0.0"
                />
              </Field>
              <div className="flex items-center justify-between rounded-lg border border-edge bg-page p-4">
                <div>
                  <p className="text-sm font-semibold">Maintenance mode</p>
                  <p className="text-xs text-ink2">Shows a maintenance notice. Never terminates active local sessions.</p>
                </div>
                <Toggle
                  checked={draft.maintenanceMode}
                  onChange={(v) => {
                    setDraft({ ...draft, maintenanceMode: v });
                    setDirty(true);
                  }}
                  label="Toggle maintenance mode"
                />
              </div>
              <div className="flex items-center justify-between rounded-lg border border-edge bg-page p-4">
                <div>
                  <p className="text-sm font-semibold">Progress layer (gamification)</p>
                  <p className="text-xs text-ink2">Levels, streaks and check-ins. Pacing only — never affects enforcement decisions.</p>
                </div>
                <Toggle
                  checked={draft.gamificationEnabled}
                  onChange={(v) => {
                    setDraft({ ...draft, gamificationEnabled: v });
                    setDirty(true);
                  }}
                  label="Toggle progress layer"
                />
              </div>
              {MULTIPLIER_FIELDS.map((f) => (
                <Field key={f.key} label={f.label} htmlFor={`cfg-${f.key}`} hint={f.hint}>
                  <Input
                    id={`cfg-${f.key}`}
                    type="number"
                    min={CONFIG_MULTIPLIER_BOUNDS[f.key].min}
                    max={CONFIG_MULTIPLIER_BOUNDS[f.key].max}
                    step={0.1}
                    value={rawInputs[f.key] ?? draft[f.key]}
                    onChange={(e) => setMultiplier(f.key, e.target.value)}
                    onBlur={() => commitMultiplier(f.key)}
                  />
                </Field>
              ))}

              {/* v2.2 Phase D — payments + growth. */}
              <div className="flex items-center justify-between rounded-lg border border-edge bg-page p-4">
                <div>
                  <p className="text-sm font-semibold">Payments (global kill-switch)</p>
                  <p className="text-xs text-ink2">Master switch for the bKash gateway and free-trial claims. Plan purchases via Google Play are unaffected.</p>
                </div>
                <Toggle
                  checked={draft.paymentsEnabled}
                  onChange={(v) => {
                    setDraft({ ...draft, paymentsEnabled: v });
                    setDirty(true);
                  }}
                  label="Toggle payments"
                />
              </div>
              {STRING_FIELDS.map((f) => (
                <Field key={f.key} label={f.label} htmlFor={`cfg-${f.key}`} hint={f.hint}>
                  {f.multiline === true ? (
                    <Textarea
                      id={`cfg-${f.key}`}
                      rows={4}
                      maxLength={CONFIG_STRING_BOUNDS[f.key].max}
                      value={draft[f.key]}
                      onChange={(e) => {
                        setDraft({ ...draft, [f.key]: e.target.value });
                        setDirty(true);
                      }}
                    />
                  ) : (
                    <Input
                      id={`cfg-${f.key}`}
                      maxLength={CONFIG_STRING_BOUNDS[f.key].max}
                      value={draft[f.key]}
                      onChange={(e) => {
                        setDraft({ ...draft, [f.key]: e.target.value });
                        setDirty(true);
                      }}
                    />
                  )}
                </Field>
              ))}
              <div className="flex items-center justify-between rounded-lg border border-edge bg-page p-4">
                <div>
                  <p className="text-sm font-semibold">Daily insight nudge</p>
                  <p className="text-xs text-ink2">One neutral evening summary of focus time and blocked attempts. No shaming copy — ever.</p>
                </div>
                <Toggle
                  checked={draft.insightNudgeEnabled}
                  onChange={(v) => {
                    setDraft({ ...draft, insightNudgeEnabled: v });
                    setDirty(true);
                  }}
                  label="Toggle insight nudge"
                />
              </div>
            </div>
          )}
        </Card>

        <div className="space-y-6">
          <Card title="Published" description={published !== null ? `Version ${_versionOf(published)} — live for clients` : 'Nothing published yet'}>
            {published === null ? (
              <p className="text-sm text-ink2">No published configuration. Clients fall back to built-in defaults.</p>
            ) : (
              <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
                {NUMERIC_FIELDS.map((f) => (
                  <div key={f.key} className="flex justify-between gap-2 border-b border-edge/60 pb-2">
                    <dt className="text-ink2">{f.label}</dt>
                    <dd className="font-semibold tabular-nums">{published[f.key]}</dd>
                  </div>
                ))}
              </dl>
            )}
          </Card>

          <Card title="Version History" description="Every version is retained — rollback re-publishes, never destroys.">
            {loading ? (
              <Skeleton className="h-40" />
            ) : (
              <ul className="divide-y divide-edge/60">
                {(data?.versions ?? []).map((v) => (
                  <li key={v.version} className="flex items-center justify-between gap-3 py-3">
                    <div>
                      <p className="text-sm font-medium">
                        v{v.version}{' '}
                        <Badge tone={v.status === 'PUBLISHED' ? 'success' : v.status === 'DRAFT' ? 'warning' : 'neutral'}>
                          {v.status}
                        </Badge>
                      </p>
                      <p className="text-xs text-ink2">
                        {v.publishedAt !== null ? `Published ${formatDateTime(v.publishedAt)}` : `Created ${formatDateTime(v.createdAt)}`}
                        {v.updatedBy !== null ? ` by ${v.updatedBy}` : ''}
                      </p>
                    </div>
                    {v.status === 'ARCHIVED' ? (
                      <Button
                        variant="secondary"
                        onClick={() => setRollbackVersion(v.version)}
                        disabled={rollingBack}
                      >
                        <Undo2 className="h-4 w-4" aria-hidden="true" />
                        Rollback
                      </Button>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>

      {/* Review changes diff */}
      <Modal
        open={diffModal}
        title="Change Summary"
        onClose={() => setDiffModal(false)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setDiffModal(false)}>Close</Button>
            <Button
              variant="danger"
              onClick={() => {
                setDiffModal(false);
                setPublishModal(true);
              }}
              disabled={changedFields.length === 0}
            >
              Continue to Publish
            </Button>
          </>
        }
      >
        {changedFields.length === 0 ? (
          <p className="text-sm text-ink2">The draft is identical to the published configuration.</p>
        ) : (
          <ul className="space-y-2">
            {changedFields.map((c) => (
              <li key={c.label} className="flex items-center justify-between gap-4 rounded-lg border border-edge bg-page px-4 py-3 text-sm">
                <span className="text-ink2">{c.label}</span>
                <span className="flex items-center gap-2 font-medium">
                  <span className="text-ink2 line-through">{c.from}</span>
                  <span aria-hidden="true">→</span>
                  <span className="text-accent">{c.to}</span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Modal>

      {/* Publish: type-to-confirm */}
      <ConfirmModal
        open={publishModal}
        title="Publish configuration"
        message={
          <>
            Publishing makes these values live for all clients on their next config fetch. Active sessions keep their
            own policy snapshot — remote config never weakens an in-flight session.
            {changedFields.length > 0 ? (
              <span className="mt-2 block font-semibold">{changedFields.length} field(s) will change.</span>
            ) : null}
          </>
        }
        confirmLabel="Publish"
        danger
        requireText="PUBLISH"
        loading={publishing}
        onConfirm={publish}
        onCancel={() => setPublishModal(false)}
      />

      {/* Rollback */}
      <ConfirmModal
        open={rollbackVersion !== null}
        title={`Roll back to v${rollbackVersion ?? ''}`}
        message="The selected version's values will be re-published as a NEW version. History is never destroyed."
        confirmLabel="Rollback"
        requireText="ROLLBACK"
        loading={rollingBack}
        onConfirm={rollback}
        onCancel={() => setRollbackVersion(null)}
      />
    </div>
  );
}

function _versionOf(doc: ConfigDoc | null | undefined): string {
  const v = doc?._version;
  return v === undefined ? '?' : String(v);
}

function rid(err: unknown): string | undefined {
  if (typeof err === 'object' && err !== null && 'requestId' in err) {
    const r = (err as { requestId?: unknown }).requestId;
    return typeof r === 'string' ? r : undefined;
  }
  return undefined;
}
