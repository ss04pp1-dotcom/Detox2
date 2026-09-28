/**
 * Detection Rules (v2.5.8) — the reels/shorts signature control page.
 *
 * WHY THIS PAGE EXISTS: reels detection matches per-app View IDs /
 * content-description hints / URL shapes. When YouTube, Instagram, TikTok or
 * Facebook rename those resources in an app update, detection silently
 * degrades until the next app release. Publishing a new ruleset here heals
 * every client within one 15-minute sync cycle — no app update needed.
 *
 * Workflow (mirrors Remote Config):
 *   load defaults or draft -> edit per-platform signature lists ->
 *   Save Draft (PUT, strict server validation) -> Publish (type-to-confirm).
 *   Reset-to-defaults writes a fresh draft from the frozen compiled set.
 *
 * What can NEVER be edited here: strategy shapes, escalation ladder,
 * debounces, BFS budgets — those are compiled into the app (security
 * boundary; see worker services/detectionRules.ts).
 */
import { useEffect, useMemo, useState } from 'react';
import { History, RotateCcw, Save, UploadCloud } from 'lucide-react';
import { api } from '../api/client';
import type {
  DetectionPlatformId,
  DetectionRulesDoc,
  PlatformRules,
} from '../api/types';
import { DETECTION_LIST_FIELDS } from '../api/types';
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

const PLATFORM_ORDER: DetectionPlatformId[] = [
  'youtube',
  'tiktok',
  'facebook',
  'facebook_lite',
  'instagram',
];

const PLATFORM_LABELS: Record<DetectionPlatformId, string> = {
  youtube: 'YouTube (Shorts)',
  tiktok: 'TikTok (+ regional) — package gate',
  facebook: 'Facebook (Reels)',
  facebook_lite: 'Facebook Lite',
  instagram: 'Instagram (Reels, full + lite)',
};

function rid(err: unknown): string | undefined {
  if (err && typeof err === 'object' && 'requestId' in err) {
    return (err as { requestId?: string }).requestId;
  }
  return undefined;
}

/** Deep copy a rules doc for editing. */
function cloneDoc(doc: DetectionRulesDoc): DetectionRulesDoc {
  return JSON.parse(JSON.stringify(doc)) as DetectionRulesDoc;
}

export default function DetectionRulesPage(): JSX.Element {
  const toast = useToast();
  const { data, loading, error, reload } = useApiData(() => api.getDetectionRules(), []);

  const [draft, setDraft] = useState<DetectionRulesDoc | null>(null);
  const [saving, setSaving] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);

  useEffect(() => {
    if (data !== null && draft === null) {
      setDraft(data.draft !== null ? cloneDoc(data.draft) : cloneDoc(data.defaults));
    }
  }, [data, draft]);

  const dirty = useMemo(() => {
    if (data === null || draft === null) return false;
    const baseline = data.draft ?? data.defaults;
    return JSON.stringify(draft) !== JSON.stringify(baseline);
  }, [data, draft]);

  if (loading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }
  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }
  if (data === null) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  const saveDraft = async (): Promise<void> => {
    if (draft === null) return;
    setSaving(true);
    try {
      const saved = await api.saveDetectionRulesDraft(draft);
      setDraft(cloneDoc(saved));
      toast.success('Draft saved — publish to push it to every app');
      reload();
    } catch (err) {
      toast.error('Draft rejected by validation', rid(err));
    } finally {
      setSaving(false);
    }
  };

  const publish = async (): Promise<void> => {
    setPublishOpen(false);
    try {
      await api.publishDetectionRules();
      toast.success('Ruleset published — clients pick it up within 15 minutes');
      setDraft(null);
      reload();
    } catch (err) {
      toast.error('Failed to publish', rid(err));
    }
  };

  const resetToDefaults = async (): Promise<void> => {
    setResetOpen(false);
    try {
      const fresh = await api.resetDetectionRules();
      setDraft(cloneDoc(fresh));
      toast.success('Draft reset to the frozen compiled defaults');
      reload();
    } catch (err) {
      toast.error('Failed to reset', rid(err));
    }
  };

  const platformRule = (key: DetectionPlatformId): PlatformRules =>
    draft?.platforms[key] ?? { enabled: true };

  const setPlatformRule = (key: DetectionPlatformId, next: PlatformRules): void => {
    if (draft === null) return;
    setDraft({ ...draft, platforms: { ...draft.platforms, [key]: next } });
  };

  const setMinAppVersion = (value: string): void => {
    if (draft === null) return;
    setDraft({ ...draft, minAppVersion: value });
  };

  return (
    <>
      <PageHeader
        title="Detection Rules"
        description="Server-pushed reels/shorts detection signatures. When a platform app update renames a View ID or label, publish fresh signatures here — clients heal within one sync cycle, no app release required."
        actions={
          <>
            <Button variant="secondary" onClick={() => setHistoryOpen(true)}>
              <History className="h-4 w-4" aria-hidden="true" /> History
            </Button>
            <Button variant="secondary" onClick={() => setResetOpen(true)}>
              <RotateCcw className="h-4 w-4" aria-hidden="true" /> Reset draft
            </Button>
            <Button variant="secondary" onClick={saveDraft} disabled={!dirty || saving}>
              <Save className="h-4 w-4" aria-hidden="true" /> Save draft
            </Button>
            <Button onClick={() => setPublishOpen(true)} disabled={dirty || saving}>
              <UploadCloud className="h-4 w-4" aria-hidden="true" /> Publish
            </Button>
          </>
        }
      />

      <div className="space-y-4">
        <Card title="Ruleset" description="Versioning mirrors Remote Config: draft -> publish -> archived history." padded>
          <div className="flex flex-wrap items-center gap-3">
            <Badge tone="info">schema v{draft?.schemaVersion ?? 1}</Badge>
            {data.published !== null ? (
              <Badge tone="success">published v{data.published._version}</Badge>
            ) : (
              <Badge tone="warning">nothing published — apps use compiled defaults</Badge>
            )}
            {data.draft !== null ? <Badge tone="neutral">draft v{data.draft._version}</Badge> : null}
            {dirty ? <Badge tone="warning">unsaved edits</Badge> : null}
            <div className="w-40">
              <Field label="Min app version" htmlFor="min-app-version">
                <Input
                  id="min-app-version"
                  value={draft?.minAppVersion ?? '1.0.0'}
                  onChange={(e) => setMinAppVersion(e.target.value)}
                  placeholder="1.0.0"
                />
              </Field>
            </div>
          </div>
          <p className="mt-3 text-xs text-ink2">
            A platform entry is a <strong>complete replacement</strong> for that platform; platforms
            left at defaults keep the compiled signatures. <strong>enabled=false</strong> turns that
            platform&apos;s detection off entirely. Unknown keys, non-matching charsets and
            oversized lists are rejected whole by the server validator.
          </p>
        </Card>

        {PLATFORM_ORDER.map((platform) => {
          const rule = platformRule(platform);
          const fields = DETECTION_LIST_FIELDS.filter((f) => f.platforms.includes(platform));
          const isGate = platform === 'tiktok';
          const baseline = (data.draft ?? data.defaults).platforms[platform];
          const changed = JSON.stringify(rule) !== JSON.stringify(baseline ?? { enabled: true });
          return (
            <Card
              key={platform}
              title={PLATFORM_LABELS[platform]}
              actions={
                <div className="flex items-center gap-2">
                  {changed ? <Badge tone="warning">edited</Badge> : null}
                  <Toggle
                    checked={rule.enabled}
                    onChange={(enabled) => setPlatformRule(platform, { ...rule, enabled })}
                    label={rule.enabled ? 'Detection on' : 'Detection off'}
                  />
                </div>
              }
              padded
            >
              {!rule.enabled ? (
                <p className="text-sm text-ink2">
                  Detection for this platform is disabled — clients skip it entirely until
                  re-enabled.
                </p>
              ) : isGate ? (
                <p className="text-sm text-ink2">
                  Package gate: the entire app is treated as a short-form feed the moment it is in
                  the foreground. No signature lists needed; nothing to edit.
                </p>
              ) : (
                <div className="grid gap-4 md:grid-cols-2">
                  {fields.map((field) => (
                    <Field
                      key={String(field.key)}
                      label={field.label}
                      hint={`${field.hint} One entry per line (max 25, 3–64 chars).`}
                    >
                      <Textarea
                        rows={Math.min(6, Math.max(3, ((rule[field.key] as string[] | undefined) ?? []).length || 3))}
                        value={(((rule[field.key] as string[] | undefined) ?? []).join('\n'))}
                        onChange={(e) =>
                          setPlatformRule(platform, {
                            ...rule,
                            [field.key]: e.target.value
                              .split('\n')
                              .map((line) => line.trim())
                              .filter((line) => line.length > 0),
                          })
                        }
                        placeholder="reel_watch_fragment_root"
                      />
                    </Field>
                  ))}
                  {platform === 'facebook_lite' ? (
                    <Field
                      label="Immersive gate"
                      hint="Require the matched video node to cover at least half the screen (kills false positives on inline feed videos)."
                    >
                      <Toggle
                        checked={rule.immersiveGate ?? false}
                        onChange={(immersiveGate) => setPlatformRule(platform, { ...rule, immersiveGate })}
                        label={rule.immersiveGate ? 'Half-screen check on' : 'Match any size'}
                      />
                    </Field>
                  ) : null}
                </div>
              )}
            </Card>
          );
        })}
      </div>

      <ConfirmModal
        open={publishOpen}
        title="Publish detection rules?"
        message="Every signed-in client applies this ruleset within one 15-minute sync cycle (or at next cold start). Bad signatures can cause false positives — review the diff before publishing."
        confirmLabel="Publish ruleset"
        requireText="PUBLISH"
        loading={false}
        onConfirm={publish}
        onCancel={() => setPublishOpen(false)}
      />
      <ConfirmModal
        open={resetOpen}
        title="Reset draft to compiled defaults?"
        message="This overwrites the current draft with the frozen compiled signature set (the app's shipped behavior). The published ruleset is not touched until you publish again."
        confirmLabel="Reset draft"
        loading={false}
        onConfirm={resetToDefaults}
        onCancel={() => setResetOpen(false)}
      />
      <Modal open={historyOpen} title="Ruleset history" onClose={() => setHistoryOpen(false)}>
        <div className="space-y-2">
          {data.versions.length === 0 ? (
            <p className="text-sm text-ink2">No versions yet.</p>
          ) : (
            data.versions.map((v) => (
              <div key={v.version} className="flex items-center justify-between rounded-lg border border-edge bg-elevated px-3 py-2 text-sm">
                <span className="font-mono">v{v.version}</span>
                <Badge
                  tone={v.status === 'PUBLISHED' ? 'success' : v.status === 'DRAFT' ? 'info' : 'neutral'}
                >
                  {v.status.toLowerCase()}
                </Badge>
                <span className="text-xs text-ink2">{v.createdBy ?? '—'}</span>
                <span className="text-xs text-ink2">{formatDateTime(v.createdAt)}</span>
              </div>
            ))
          )}
        </div>
      </Modal>
    </>
  );
}
