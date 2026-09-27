/**
 * Support tickets: triage board with status/priority filters, detail drawer
 * with response + status workflow.
 */
import { useMemo, useState } from 'react';
import { api } from '../api/client';
import type { Ticket, TicketStatus } from '../api/types';
import { usePaginatedQuery } from '../lib/hooks';
import {
  Badge,
  Button,
  Card,
  ErrorNotice,
  Field,
  Modal,
  PageHeader,
  Select,
  Skeleton,
  Table,
  Textarea,
  useToast,
  type TableColumn,
} from '../components/ui';
import { formatDateTime } from '../lib/format';

const STATUSES: TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING_USER', 'RESOLVED', 'CLOSED'];

function statusTone(s: TicketStatus): 'info' | 'warning' | 'success' | 'neutral' {
  if (s === 'OPEN') return 'warning';
  if (s === 'IN_PROGRESS') return 'info';
  if (s === 'WAITING_USER') return 'neutral';
  return 'success';
}

function priorityTone(p: Ticket['priority']): 'neutral' | 'warning' | 'danger' {
  if (p === 'LOW') return 'neutral';
  if (p === 'URGENT') return 'danger';
  return 'warning';
}

/** The SPA uses subject; the worker serializer sends category. */
function categoryOf(t: Ticket): string {
  return t.category ?? t.subject ?? '—';
}

export default function SupportPage(): JSX.Element {
  const toast = useToast();
  const [status, setStatus] = useState('');
  const queryKey = status;

  const { rows, loading, error, reload, page, hasPrev, hasNext, prev, next } = usePaginatedQuery<Ticket>(
    queryKey,
    (cursor) => api.listTickets({ status: status || undefined, cursor, limit: 50 }),
  );

  const [active, setActive] = useState<Ticket | null>(null);
  const [reply, setReply] = useState('');
  const [nextStatus, setNextStatus] = useState<TicketStatus>('IN_PROGRESS');
  const [busy, setBusy] = useState(false);

  const open = (t: Ticket): void => {
    setActive(t);
    setReply('');
    setNextStatus(t.status === 'OPEN' ? 'IN_PROGRESS' : t.status);
  };

  const submit = async (): Promise<void> => {
    if (active === null || busy) return;
    setBusy(true);
    try {
      await api.updateTicket(active.id, {
        status: nextStatus,
        response: reply.trim().length > 0 ? reply.trim() : undefined,
      });
      toast.success('Ticket updated');
      setActive(null);
      reload();
    } catch (err) {
      toast.error('Failed to update ticket', rid(err));
    } finally {
      setBusy(false);
    }
  };

  const columns: Array<TableColumn<Ticket>> = useMemo(
    () => [
      { key: 'id', header: 'Ticket', render: (t) => <span className="font-mono text-xs text-ink2">{t.id}</span> },
      { key: 'user', header: 'User', render: (t) => <span className="font-medium text-ink">{t.userEmail ?? t.userId}</span> },
      { key: 'category', header: 'Category', render: (t) => <span className="text-ink2">{categoryOf(t)}</span> },
      { key: 'priority', header: 'Priority', render: (t) => <Badge tone={priorityTone(t.priority)}>{t.priority}</Badge> },
      { key: 'status', header: 'Status', render: (t) => <Badge tone={statusTone(t.status)}>{t.status}</Badge> },
      { key: 'created', header: 'Created', render: (t) => <span className="text-ink2">{formatDateTime(t.createdAt)}</span> },
    ],
    [],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="Support Tickets"
        description="User-submitted issues with request IDs for log correlation. No raw device screen content is ever attached."
      />

      <Select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Filter by ticket status" className="max-w-48">
        <option value="">All statuses</option>
        {STATUSES.map((s) => (
          <option key={s} value={s}>{s}</option>
        ))}
      </Select>

      {error !== null ? <ErrorNotice error={error} onRetry={reload} /> : null}

      <Card padded={false}>
        <Table
          columns={columns}
          rows={rows}
          rowKey={(t) => t.id}
          loading={loading}
          onRowClick={open}
          emptyTitle="No tickets"
          emptyMessage="Support requests will appear here."
          pagination={{ page, hasPrev, hasNext, onPrev: prev, onNext: next, loadedCount: rows.length }}
        />
      </Card>

      <Modal
        open={active !== null}
        title={active !== null ? `Ticket ${active.id}` : ''}
        onClose={() => setActive(null)}
        wide
        footer={
          <>
            <Button variant="secondary" onClick={() => setActive(null)}>Close</Button>
            <Button onClick={submit} disabled={busy}>
              {busy ? 'Updating…' : 'Update ticket'}
            </Button>
          </>
        }
      >
        {active !== null ? (
          <div className="space-y-4">
            <div className="rounded-lg border border-edge bg-page p-4 text-sm">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <Badge tone={statusTone(active.status)}>{active.status}</Badge>
                <Badge tone={priorityTone(active.priority)}>{active.priority}</Badge>
                <span className="text-xs text-ink2">{formatDateTime(active.createdAt)}</span>
              </div>
              <p className="font-medium">{categoryOf(active)}</p>
              <p className="mt-1 whitespace-pre-wrap text-ink2">{active.description}</p>
              <p className="mt-3 text-xs text-ink2">
                App {active.appVersion} · Request ID <span className="font-mono">{active.requestId}</span>
              </p>
            </div>

            {/* Previous replies — the SPA contract models a thread
                (responses[]); the worker stores a single overwritten
                `response` string, shown as the latest reply. */}
            {(active.responses ?? []).length > 0 ? (
              <div className="space-y-2">
                <p className="text-[10px] font-bold uppercase tracking-widest text-ink2">Previous replies</p>
                {(active.responses ?? []).map((r) => (
                  <div key={r.id} className="rounded-lg border border-edge bg-page p-3 text-sm">
                    <p className="text-xs text-ink2">{r.authorEmail} · {formatDateTime(r.createdAt)}</p>
                    <p className="mt-1 whitespace-pre-wrap text-ink">{r.body}</p>
                  </div>
                ))}
              </div>
            ) : typeof active.response === 'string' && active.response.length > 0 ? (
              <div className="space-y-2">
                <p className="text-[10px] font-bold uppercase tracking-widest text-ink2">Latest reply</p>
                <div className="rounded-lg border border-edge bg-page p-3 text-sm">
                  <p className="whitespace-pre-wrap text-ink">{active.response}</p>
                </div>
              </div>
            ) : null}

            <Field label="Status" htmlFor="ticket-status">
              <Select id="ticket-status" value={nextStatus} onChange={(e) => setNextStatus(e.target.value as TicketStatus)}>
                {STATUSES.map((s) => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </Select>
            </Field>

            <Field label="Response to user" htmlFor="ticket-reply" hint="Optional — shown to the user in-app.">
              <Textarea id="ticket-reply" rows={4} value={reply} onChange={(e) => setReply(e.target.value)} placeholder="Write a response…" />
            </Field>
          </div>
        ) : (
          <Skeleton className="h-40" />
        )}
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
