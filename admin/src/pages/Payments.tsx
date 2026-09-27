/**
 * Payments (v2.2 Phase D): manual bKash review queue.
 *
 * The reviewer compares each IN_REVIEW payment's TrxID + reference + amount
 * against the merchant bKash statement, then verifies (grants the plan's
 * PRO days, extending any active entitlement) or rejects with a reason
 * (audited + security event on the server).
 */
import { useCallback, useMemo, useState } from 'react';
import { BadgeCheck, Banknote, ClipboardCheck, Hourglass, Wallet } from 'lucide-react';
import { api, toApiError } from '../api/client';
import type { BkashPayment, BkashPaymentsSummary } from '../api/types';
import { useApiData, usePaginatedQuery } from '../lib/hooks';
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
  StatCard,
  Table,
  type TableColumn,
  useToast,
} from '../components/ui';
import { formatDateTime, formatNumber } from '../lib/format';

const STATUS_FILTERS = ['', 'IN_REVIEW', 'PENDING', 'VERIFIED', 'REJECTED', 'EXPIRED', 'CANCELED'] as const;

const STATUS_TONE: Record<BkashPayment['status'], 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'accent'> = {
  PENDING: 'neutral',
  IN_REVIEW: 'warning',
  VERIFIED: 'success',
  REJECTED: 'danger',
  EXPIRED: 'neutral',
  CANCELED: 'neutral',
};

function formatBdt(minor: number): string {
  return `৳${(minor / 100).toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`;
}

export default function PaymentsPage(): JSX.Element {
  const toast = useToast();
  const [status, setStatus] = useState<string>('IN_REVIEW');

  const query = usePaginatedQuery<BkashPayment>(
    status,
    (cursor) => api.listBkashPayments({ status: status || undefined, cursor, limit: 50 }),
  );
  const summaryQuery = useApiData<BkashPaymentsSummary>(
    async () => (await api.listBkashPayments({ limit: 1 })).summary,
    [status],
  );

  const reload = useCallback(() => {
    query.reload();
    summaryQuery.reload();
  }, [query, summaryQuery]);

  const { rows, loading, error, page, hasPrev, hasNext, prev, next } = query;

  const [verifying, setVerifying] = useState<BkashPayment | null>(null);
  const [rejecting, setRejecting] = useState<BkashPayment | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [busy, setBusy] = useState(false);

  const summary = summaryQuery.data ?? { inReview: 0, verified: 0, verifiedAmountMinor: 0 };

  const confirmVerify = async (): Promise<void> => {
    if (verifying === null) return;
    setBusy(true);
    try {
      await api.verifyBkashPayment(verifying.id);
      toast.success(`Verified — ${verifying.planDays ?? 0} PRO days granted to ${verifying.userEmail ?? 'user'}`);
      setVerifying(null);
      await reload();
    } catch (err) {
      const apiErr = toApiError(err);
      toast.error(`Verify failed: ${apiErr.message}`, apiErr.requestId);
    } finally {
      setBusy(false);
    }
  };

  const confirmReject = async (): Promise<void> => {
    if (rejecting === null) return;
    const reason = rejectReason.trim();
    if (reason.length < 3) {
      toast.error('Rejection reason must be at least 3 characters.');
      return;
    }
    setBusy(true);
    try {
      await api.rejectBkashPayment(rejecting.id, reason);
      toast.success('Payment rejected and security event recorded');
      setRejecting(null);
      setRejectReason('');
      await reload();
    } catch (err) {
      const apiErr = toApiError(err);
      toast.error(`Reject failed: ${apiErr.message}`, apiErr.requestId);
    } finally {
      setBusy(false);
    }
  };

  const columns: Array<TableColumn<BkashPayment>> = useMemo(
    () => [
      {
        key: 'user',
        header: 'User',
        render: (p) => (
          <div className="flex flex-col">
            <span className="font-medium text-ink">{p.userEmail ?? p.userId}</span>
            <span className="font-mono text-xs text-ink2">{p.reference}</span>
          </div>
        ),
      },
      {
        key: 'plan',
        header: 'Plan',
        render: (p) => (
          <div className="flex flex-col">
            <span className="text-ink">{p.planName ?? p.planId}</span>
            <span className="text-xs text-ink2">{p.planDays !== null ? `${formatNumber(p.planDays)} days` : ''}</span>
          </div>
        ),
      },
      { key: 'amount', header: 'Amount', align: 'right', render: (p) => <span className="tabular-nums font-medium">{formatBdt(p.amountMinor)}</span> },
      {
        key: 'trx',
        header: 'TrxID / sender',
        render: (p) => (
          <div className="flex flex-col">
            <span className="font-mono text-xs text-ink">{p.trxId ?? '— not submitted —'}</span>
            <span className="text-xs text-ink2">{p.senderNumber ?? ''}</span>
          </div>
        ),
      },
      { key: 'status', header: 'Status', render: (p) => <Badge tone={STATUS_TONE[p.status]}>{p.status.replace('_', ' ')}</Badge> },
      { key: 'created', header: 'Created', render: (p) => <span className="text-ink2">{formatDateTime(p.createdAt)}</span> },
      {
        key: 'actions',
        header: '',
        align: 'right',
        render: (p) =>
          p.status === 'IN_REVIEW' ? (
            <div className="flex justify-end gap-2">
              <Button size="sm" onClick={() => setVerifying(p)}>
                <BadgeCheck className="mr-1 h-3.5 w-3.5" aria-hidden="true" /> Verify
              </Button>
              <Button variant="danger" size="sm" onClick={() => setRejecting(p)}>
                Reject
              </Button>
            </div>
          ) : null,
      },
    ],
    [],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="bKash Payments"
        description="Manual gateway review queue. Match the TrxID and reference against the merchant bKash statement before granting PRO days."
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Awaiting review" value={formatNumber(summary.inReview)} icon={<Hourglass className="h-4 w-4" aria-hidden="true" />} accent="warn" loading={loading} />
        <StatCard label="Verified total" value={formatNumber(summary.verified)} icon={<ClipboardCheck className="h-4 w-4" aria-hidden="true" />} accent="ok" loading={loading} />
        <StatCard label="Verified volume" value={formatBdt(summary.verifiedAmountMinor)} icon={<Banknote className="h-4 w-4" aria-hidden="true" />} loading={loading} />
        <StatCard label="On this page" value={formatNumber(rows.length)} icon={<Wallet className="h-4 w-4" aria-hidden="true" />} loading={loading} />
      </div>

      <div className="flex flex-col gap-3 sm:flex-row">
        <Select
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          aria-label="Filter by payment status"
          className="sm:w-48"
        >
          {STATUS_FILTERS.map((s) => (
            <option key={s} value={s}>
              {s === '' ? 'All statuses' : s.replace('_', ' ')}
            </option>
          ))}
        </Select>
      </div>

      {error !== null ? <ErrorNotice error={error} onRetry={reload} /> : null}

      <Card padded={false}>
        <Table
          columns={columns}
          rows={rows}
          rowKey={(p) => p.id}
          loading={loading}
          emptyTitle="No payments"
          emptyMessage={status === 'IN_REVIEW' ? 'The review queue is clear.' : 'No payments match this filter.'}
          pagination={{ page, hasPrev, hasNext, onPrev: prev, onNext: next, loadedCount: rows.length }}
        />
      </Card>

      <ConfirmModal
        open={verifying !== null}
        title="Verify bKash payment"
        message={
          verifying !== null ? (
            <span>
              Grant <strong>{verifying.planDays ?? 0} PRO days</strong> to <strong>{verifying.userEmail ?? verifying.userId}</strong> for{' '}
              <strong>{formatBdt(verifying.amountMinor)}</strong>? Double-check TrxID{' '}
              <span className="font-mono">{verifying.trxId}</span> against the merchant statement first.
            </span>
          ) : null
        }
        confirmLabel="Verify and grant"
        loading={busy}
        onConfirm={() => void confirmVerify()}
        onCancel={() => setVerifying(null)}
      />

      <Modal
        open={rejecting !== null}
        title="Reject bKash payment"
        onClose={() => setRejecting(null)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRejecting(null)} disabled={busy}>
              Cancel
            </Button>
            <Button variant="danger" onClick={() => void confirmReject()} loading={busy}>
              Reject payment
            </Button>
          </>
        }
      >
        {rejecting !== null ? (
          <div className="space-y-4">
            <p className="text-sm text-ink2">
              The user will see the rejection reason in the app. A MEDIUM security event is recorded for fraud
              analysis. Reference <span className="font-mono">{rejecting.reference}</span>, TrxID{' '}
              <span className="font-mono">{rejecting.trxId ?? 'n/a'}</span>.
            </p>
            <Field label="Reason" htmlFor="reject-reason" hint="Minimum 3 characters.">
              <Input
                id="reject-reason"
                value={rejectReason}
                maxLength={300}
                onChange={(e) => setRejectReason(e.target.value)}
                placeholder="e.g. Transfer amount did not match the plan price."
              />
            </Field>
          </div>
        ) : null}
      </Modal>
    </div>
  );
}
