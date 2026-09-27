/**
 * Announcements: create / edit / publish / archive product announcements
 * with type badges and schedule windows.
 */
import { useState } from 'react';
import { Megaphone, Plus } from 'lucide-react';
import { api } from '../api/client';
import type { Announcement, AnnouncementInput, AnnouncementType } from '../api/types';
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
  Select,
  Skeleton,
  Textarea,
  useToast,
} from '../components/ui';
import { formatDateTime, fromLocalInputValue, toLocalInputValue } from '../lib/format';

const TYPES: AnnouncementType[] = ['INFO', 'UPDATE', 'WARNING', 'MAINTENANCE', 'PROMOTION'];

const TYPE_TONES: Record<AnnouncementType, 'info' | 'accent' | 'warning' | 'danger' | 'success'> = {
  INFO: 'info',
  UPDATE: 'accent',
  WARNING: 'warning',
  MAINTENANCE: 'warning',
  PROMOTION: 'success',
};

/** The SPA stores startAt/endAt; the worker serializer sends startTime/endTime. */
function annStart(a: Announcement): string {
  return a.startTime ?? a.startAt;
}

function annEnd(a: Announcement): string | null {
  return a.endTime ?? a.endAt;
}

export default function AnnouncementsPage(): JSX.Element {
  const toast = useToast();
  const { data, loading, error, reload } = useApiData(() => api.listAnnouncements(), []);

  const [modal, setModal] = useState(false);
  const [editing, setEditing] = useState<Announcement | null>(null);
  const [archiveTarget, setArchiveTarget] = useState<Announcement | null>(null);
  const [busy, setBusy] = useState(false);

  const [form, setForm] = useState<AnnouncementInput>({
    title: '',
    body: '',
    type: 'INFO',
    targetRule: 'all',
    startAt: toLocalInputValue(new Date().toISOString()),
    endAt: null,
  });

  const openCreate = (): void => {
    setEditing(null);
    setForm({
      title: '',
      body: '',
      type: 'INFO',
      targetRule: 'all',
      startAt: toLocalInputValue(new Date().toISOString()),
      endAt: null,
    });
    setModal(true);
  };

  const openEdit = (a: Announcement): void => {
    setEditing(a);
    setForm({
      title: a.title,
      body: a.body,
      type: a.type,
      targetRule: a.targetRule,
      startAt: toLocalInputValue(annStart(a)),
      endAt: annEnd(a) === null ? null : toLocalInputValue(annEnd(a)),
    });
    setModal(true);
  };

  const submit = async (): Promise<void> => {
    if (busy) return;
    setBusy(true);
    try {
      // datetime-local inputs hold local wall-clock strings — convert to ISO
      // UTC before sending so the schedule does not shift by the admin's
      // UTC offset when the worker (TZ=UTC) parses it.
      const payload: AnnouncementInput = {
        ...form,
        startAt: fromLocalInputValue(form.startAt) ?? form.startAt,
        endAt: form.endAt === null ? null : fromLocalInputValue(form.endAt) ?? form.endAt,
      };
      if (editing === null) {
        await api.createAnnouncement(payload);
        toast.success('Announcement created');
      } else {
        await api.updateAnnouncement(editing.id, payload);
        toast.success('Announcement updated');
      }
      setModal(false);
      reload();
    } catch (err) {
      toast.error('Failed to save announcement', rid(err));
    } finally {
      setBusy(false);
    }
  };

  const archive = async (): Promise<void> => {
    if (archiveTarget === null || busy) return;
    setBusy(true);
    try {
      // The worker's status vocabulary is DRAFT | ACTIVE | ARCHIVED.
      await api.updateAnnouncement(archiveTarget.id, { status: 'ARCHIVED' });
      toast.success('Announcement archived');
      setArchiveTarget(null);
      reload();
    } catch (err) {
      toast.error('Failed to archive', rid(err));
    } finally {
      setBusy(false);
    }
  };

  if (error !== null) {
    return <ErrorNotice error={error} onRetry={reload} />;
  }

  const valid = form.title.trim().length >= 3 && form.body.trim().length >= 3 && form.startAt.length > 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Announcements"
        description="In-app messages targeted by app version and schedule. Avoid unnecessary personal targeting."
        actions={
          <Button onClick={openCreate}>
            <Plus className="h-4 w-4" aria-hidden="true" />
            New Announcement
          </Button>
        }
      />

      {loading ? (
        <Skeleton className="h-64" />
      ) : (
        <Card padded={false}>
          <ul className="divide-y divide-edge/60">
            {(data ?? []).map((a) => (
              <li key={a.id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="font-semibold">{a.title}</p>
                    <Badge tone={TYPE_TONES[a.type]}>
                      <Megaphone className="h-3 w-3" aria-hidden="true" />
                      {a.type}
                    </Badge>
                    <Badge tone={a.status === 'PUBLISHED' || a.status === 'ACTIVE' ? 'success' : 'neutral'}>{a.status}</Badge>
                  </div>
                  <p className="mt-1 line-clamp-2 max-w-2xl text-sm text-ink2">{a.body}</p>
                  <p className="mt-1 text-xs text-ink2">
                    {formatDateTime(annStart(a))} → {annEnd(a) === null ? 'no end' : formatDateTime(annEnd(a))} · target: {a.targetRule}
                  </p>
                </div>
                <div className="flex shrink-0 gap-2">
                  <Button variant="secondary" onClick={() => openEdit(a)}>Edit</Button>
                  {a.status !== 'EXPIRED' && a.status !== 'ARCHIVED' ? (
                    <Button variant="danger" onClick={() => setArchiveTarget(a)}>Archive</Button>
                  ) : null}
                </div>
              </li>
            ))}
            {(data ?? []).length === 0 ? (
              <li className="p-8 text-center text-sm text-ink2">
                No announcements yet. Create the first one to inform users about updates.
              </li>
            ) : null}
          </ul>
        </Card>
      )}

      <Modal
        open={modal}
        title={editing === null ? 'New Announcement' : `Edit: ${editing.title}`}
        onClose={() => setModal(false)}
        wide
        footer={
          <>
            <Button variant="secondary" onClick={() => setModal(false)}>Cancel</Button>
            <Button onClick={submit} disabled={!valid || busy}>
              {busy ? 'Saving…' : editing === null ? 'Create' : 'Save changes'}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Title" htmlFor="ann-title">
            <Input id="ann-title" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} maxLength={200} placeholder="Version 1.1 is here" />
          </Field>
          <Field label="Body" htmlFor="ann-body">
            <Textarea id="ann-body" rows={4} value={form.body} onChange={(e) => setForm({ ...form, body: e.target.value })} maxLength={5000} placeholder="What should users know?" />
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Type" htmlFor="ann-type">
              <Select id="ann-type" value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as AnnouncementType })}>
                {TYPES.map((t) => (
                  <option key={t} value={t}>{t}</option>
                ))}
              </Select>
            </Field>
            <Field label="Target rule" htmlFor="ann-target" hint="e.g. all, min_version:1.1.0">
              <Input id="ann-target" value={form.targetRule} onChange={(e) => setForm({ ...form, targetRule: e.target.value })} placeholder="all" />
            </Field>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Starts at" htmlFor="ann-start">
              <Input id="ann-start" type="datetime-local" value={form.startAt} onChange={(e) => setForm({ ...form, startAt: e.target.value })} />
            </Field>
            <Field label="Ends at (optional)" htmlFor="ann-end">
              <Input id="ann-end" type="datetime-local" value={form.endAt ?? ''} onChange={(e) => setForm({ ...form, endAt: e.target.value === '' ? null : e.target.value })} />
            </Field>
          </div>
        </div>
      </Modal>

      <ConfirmModal
        open={archiveTarget !== null}
        title="Archive announcement"
        message={`"${archiveTarget?.title ?? ''}" will stop being shown to users.`}
        confirmLabel="Archive"
        danger
        loading={busy}
        onConfirm={archive}
        onCancel={() => setArchiveTarget(null)}
      />
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
