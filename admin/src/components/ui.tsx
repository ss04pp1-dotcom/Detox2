/**
 * MAXLEVEL DETOX Admin — UI kit.
 * Custom Tailwind components (no component library): Button, Card, StatCard,
 * Badge, Toggle, form fields, Modal + ConfirmModal, generic Table with
 * skeleton/empty/pagination, PageHeader, Toast system and error notices.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import {
  AlertTriangle,
  ArrowDown,
  ArrowLeft,
  ArrowUp,
  ArrowUpDown,
  Ban,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Circle,
  Inbox,
  Loader2,
  Minus,
  ShieldAlert,
  TrendingDown,
  TrendingUp,
  X,
  XCircle,
} from 'lucide-react';
import { cn } from '../lib/format';
import { toApiError, type ApiError } from '../api/error';
import type { HealthStatus, SecuritySeverity, UserStatus } from '../api/types';

// ---------------------------------------------------------------------------
// Button
// ---------------------------------------------------------------------------

type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  loading?: boolean;
  size?: 'sm' | 'md';
}

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  primary: 'border border-transparent bg-gradient-to-r from-accent to-[#4D7DFF] text-white shadow-lg shadow-accent/20 hover:brightness-110',
  secondary: 'border border-edge bg-gradient-to-b from-elevated to-surface text-ink shadow-sm hover:border-accent/30 hover:bg-elevated',
  danger: 'border border-bad/40 bg-bad/10 text-bad hover:bg-bad/20',
  ghost: 'border border-transparent bg-transparent text-ink2 hover:bg-elevated hover:text-ink',
};

export function Button({
  variant = 'primary',
  loading = false,
  size = 'md',
  className,
  children,
  disabled,
  type,
  ...rest
}: ButtonProps): JSX.Element {
  return (
    <button
      type={type ?? 'button'}
      disabled={disabled === true || loading}
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-xl font-semibold transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent disabled:cursor-not-allowed disabled:opacity-50',
        size === 'sm' ? 'px-2.5 py-1.5 text-xs' : 'px-4 py-2 text-sm',
        BUTTON_VARIANTS[variant],
        className,
      )}
      {...rest}
    >
      {loading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : null}
      {children}
    </button>
  );
}

// ---------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------

interface CardProps {
  children: ReactNode;
  className?: string;
  title?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  padded?: boolean;
}

export function Card({ children, className, title, description, actions, padded = true }: CardProps): JSX.Element {
  const hasHeader = title !== undefined || description !== undefined || actions !== undefined;
  return (
    <section className={cn('mld-glass overflow-hidden rounded-2xl border border-edge/80', className)}>
      {hasHeader ? (
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-edge px-5 py-4">
          <div className="min-w-0">
            {title !== undefined ? <h2 className="text-sm font-semibold text-ink">{title}</h2> : null}
            {description !== undefined ? <p className="mt-0.5 text-xs text-ink2">{description}</p> : null}
          </div>
          {actions !== undefined ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
        </header>
      ) : null}
      <div className={padded ? 'p-5' : undefined}>{children}</div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// StatCard
// ---------------------------------------------------------------------------

export type StatAccent = 'accent' | 'ok' | 'warn' | 'bad' | 'info';
export type DeltaTone = 'up' | 'down' | 'flat';

interface StatCardProps {
  label: string;
  value: ReactNode;
  delta?: string;
  deltaTone?: DeltaTone;
  icon?: ReactNode;
  accent?: StatAccent;
  loading?: boolean;
}

const STAT_ACCENTS: Record<StatAccent, string> = {
  accent: 'bg-accent/15 text-indigo-300',
  ok: 'bg-ok/15 text-ok',
  warn: 'bg-warn/15 text-warn',
  bad: 'bg-bad/15 text-bad',
  info: 'bg-info/15 text-info',
};

export function StatCard({
  label,
  value,
  delta,
  deltaTone = 'flat',
  icon,
  accent = 'accent',
  loading = false,
}: StatCardProps): JSX.Element {
  return (
    <div className="mld-glass rounded-2xl border border-edge/80 p-4 shadow-glow">
      <div className="flex items-start justify-between gap-2">
        <p className="text-xs font-medium uppercase tracking-wide text-ink2">{label}</p>
        {icon !== undefined ? <span className={cn('shrink-0 rounded-lg p-2', STAT_ACCENTS[accent])}>{icon}</span> : null}
      </div>
      {loading ? (
        <div className="mt-2.5 h-7 w-24 animate-pulse rounded bg-elevated" aria-label="Loading value" />
      ) : (
        <p className="mt-2.5 text-2xl font-bold tracking-tight text-ink">{value}</p>
      )}
      {delta !== undefined && !loading ? (
        <p
          className={cn(
            'mt-1 flex items-center gap-1 text-xs font-medium',
            deltaTone === 'up' && 'text-ok',
            deltaTone === 'down' && 'text-bad',
            deltaTone === 'flat' && 'text-ink2',
          )}
        >
          {deltaTone === 'up' ? (
            <TrendingUp className="h-3.5 w-3.5" aria-hidden="true" />
          ) : deltaTone === 'down' ? (
            <TrendingDown className="h-3.5 w-3.5" aria-hidden="true" />
          ) : (
            <Minus className="h-3.5 w-3.5" aria-hidden="true" />
          )}
          {delta}
        </p>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Badge
// ---------------------------------------------------------------------------

export type BadgeTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'accent';

interface BadgeProps {
  tone: BadgeTone;
  icon?: ReactNode;
  children: ReactNode;
  className?: string;
}

const BADGE_TONES: Record<BadgeTone, string> = {
  success: 'border-ok/30 bg-ok/10 text-ok',
  warning: 'border-warn/30 bg-warn/10 text-warn',
  danger: 'border-bad/30 bg-bad/10 text-bad',
  info: 'border-info/30 bg-info/10 text-info',
  neutral: 'border-edge bg-slate-500/10 text-ink2',
  accent: 'border-accent/30 bg-accent/10 text-indigo-300',
};

export function Badge({ tone, icon, children, className }: BadgeProps): JSX.Element {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2 py-0.5 text-[11px] font-medium',
        BADGE_TONES[tone],
        className,
      )}
    >
      {icon}
      {children}
    </span>
  );
}

// Domain badges (status color always paired with an icon — accessibility).

export function UserStatusBadge({ status }: { status: UserStatus }): JSX.Element {
  if (status === 'ACTIVE') return <Badge tone="success" icon={<CheckCircle2 className="h-3 w-3" aria-hidden="true" />}>Active</Badge>;
  if (status === 'SUSPENDED') return <Badge tone="danger" icon={<Ban className="h-3 w-3" aria-hidden="true" />}>Suspended</Badge>;
  if (status === 'BANNED') return <Badge tone="danger" icon={<Ban className="h-3 w-3" aria-hidden="true" />}>Banned</Badge>;
  if (status === 'PENDING') return <Badge tone="warning" icon={<AlertTriangle className="h-3 w-3" aria-hidden="true" />}>Pending</Badge>;
  // Unknown/legacy statuses (e.g. DELETED) render neutrally, never as a lie.
  return <Badge tone="neutral" icon={<Circle className="h-3 w-3" aria-hidden="true" />}>{status}</Badge>;
}

export function SeverityBadge({ severity }: { severity: SecuritySeverity }): JSX.Element {
  const tone = severity === 'CRITICAL' || severity === 'HIGH' ? 'danger' : severity === 'MEDIUM' ? 'warning' : 'neutral';
  return (
    <Badge tone={tone} icon={<ShieldAlert className="h-3 w-3" aria-hidden="true" />}>
      {severity}
    </Badge>
  );
}

export function HealthBadge({ status }: { status: HealthStatus }): JSX.Element {
  // 'operational' is the worker's vocabulary for a healthy probe; 'unseeded'
  // means the probe ran but the store has no seed data yet.
  if (status === 'healthy' || status === 'operational') {
    return <Badge tone="success" icon={<span className="h-2 w-2 rounded-full bg-ok" aria-hidden="true" />}>Healthy</Badge>;
  }
  if (status === 'degraded') return <Badge tone="warning" icon={<AlertTriangle className="h-3 w-3" aria-hidden="true" />}>Degraded</Badge>;
  if (status === 'unseeded') return <Badge tone="warning" icon={<AlertTriangle className="h-3 w-3" aria-hidden="true" />}>Unseeded</Badge>;
  return <Badge tone="danger" icon={<XCircle className="h-3 w-3" aria-hidden="true" />}>Down</Badge>;
}

// ---------------------------------------------------------------------------
// Toggle (switch)
// ---------------------------------------------------------------------------

interface ToggleProps {
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
  label: string;
}

export function Toggle({ checked, onChange, disabled = false, label }: ToggleProps): JSX.Element {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        'relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-2 focus-visible:ring-offset-page disabled:cursor-not-allowed disabled:opacity-50',
        checked ? 'border-accent bg-accent' : 'border-edge bg-elevated',
      )}
    >
      <span
        className={cn(
          'inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform',
          checked ? 'translate-x-6' : 'translate-x-1',
        )}
      />
    </button>
  );
}

// ---------------------------------------------------------------------------
// Form fields
// ---------------------------------------------------------------------------

interface FieldProps {
  label: ReactNode;
  htmlFor?: string;
  error?: string;
  hint?: ReactNode;
  children: ReactNode;
}

export function Field({ label, htmlFor, error, hint, children }: FieldProps): JSX.Element {
  return (
    <div className="space-y-1.5">
      <label htmlFor={htmlFor} className="block text-xs font-medium text-ink2">
        {label}
      </label>
      {children}
      {error !== undefined && error !== '' ? (
        <p className="flex items-center gap-1 text-xs text-bad" role="alert">
          <AlertTriangle className="h-3 w-3 shrink-0" aria-hidden="true" />
          {error}
        </p>
      ) : hint !== undefined ? (
        <p className="text-xs text-ink2">{hint}</p>
      ) : null}
    </div>
  );
}

export function Input({ className, ...rest }: InputHTMLAttributes<HTMLInputElement>): JSX.Element {
  return (
    <input
      className={cn(
        'w-full rounded-lg border border-edge bg-page px-3 py-2 text-sm text-ink placeholder:text-slate-500 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...rest}
    />
  );
}

export function Select({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>): JSX.Element {
  return (
    <select
      className={cn(
        'w-full cursor-pointer rounded-lg border border-edge bg-page px-3 py-2 text-sm text-ink focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...rest}
    >
      {children}
    </select>
  );
}

export function Textarea({ className, ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement>): JSX.Element {
  return (
    <textarea
      className={cn(
        'w-full rounded-lg border border-edge bg-page px-3 py-2 text-sm text-ink placeholder:text-slate-500 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...rest}
    />
  );
}

/** Label + control + error wiring in one shot. */
export function TextField({
  label,
  error,
  hint,
  ...inputProps
}: InputHTMLAttributes<HTMLInputElement> & { label: ReactNode; error?: string; hint?: ReactNode }): JSX.Element {
  const id = useId();
  return (
    <Field label={label} htmlFor={id} error={error} hint={hint}>
      <Input id={id} aria-invalid={error !== undefined && error !== ''} {...inputProps} />
    </Field>
  );
}

export function SelectField({
  label,
  error,
  hint,
  children,
  ...selectProps
}: SelectHTMLAttributes<HTMLSelectElement> & { label: ReactNode; error?: string; hint?: ReactNode }): JSX.Element {
  const id = useId();
  return (
    <Field label={label} htmlFor={id} error={error} hint={hint}>
      <Select id={id} {...selectProps}>
        {children}
      </Select>
    </Field>
  );
}

export function TextareaField({
  label,
  error,
  hint,
  ...textareaProps
}: TextareaHTMLAttributes<HTMLTextAreaElement> & { label: ReactNode; error?: string; hint?: ReactNode }): JSX.Element {
  const id = useId();
  return (
    <Field label={label} htmlFor={id} error={error} hint={hint}>
      <Textarea id={id} aria-invalid={error !== undefined && error !== ''} {...textareaProps} />
    </Field>
  );
}

// ---------------------------------------------------------------------------
// Modal + ConfirmModal
// ---------------------------------------------------------------------------

interface ModalProps {
  open: boolean;
  title: ReactNode;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}

export function Modal({ open, title, onClose, children, footer, wide = false }: ModalProps): JSX.Element | null {
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-[#04070f]/75 backdrop-blur-sm" onClick={onClose} aria-hidden="true" />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={typeof title === 'string' ? title : 'Dialog'}
        className={cn('relative flex max-h-[85vh] w-full flex-col rounded-xl border border-edge bg-surface shadow-2xl', wide ? 'max-w-2xl' : 'max-w-md')}
      >
        <header className="flex items-center justify-between gap-3 border-b border-edge px-5 py-4">
          <h2 className="text-sm font-semibold text-ink">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close dialog"
            className="rounded p-1 text-ink2 transition-colors hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
          >
            <X className="h-4 w-4" aria-hidden="true" />
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer !== undefined ? (
          <footer className="flex items-center justify-end gap-2 border-t border-edge px-5 py-4">{footer}</footer>
        ) : null}
      </div>
    </div>
  );
}

interface ConfirmModalProps {
  open: boolean;
  title: ReactNode;
  message: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
  loading?: boolean;
  /** When set, the confirm button stays disabled until the user types this exact string. */
  requireText?: string;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmModal({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  danger = false,
  loading = false,
  requireText,
  onConfirm,
  onCancel,
}: ConfirmModalProps): JSX.Element {
  const [typed, setTyped] = useState('');
  useEffect(() => {
    if (open) setTyped('');
  }, [open]);
  const locked = requireText !== undefined && typed !== requireText;

  return (
    <Modal
      open={open}
      title={title}
      onClose={onCancel}
      footer={
        <>
          <Button variant="ghost" onClick={onCancel} disabled={loading}>
            {cancelLabel}
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm} loading={loading} disabled={locked}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="flex items-start gap-3">
          <span
            className={cn(
              'shrink-0 rounded-lg p-2',
              danger ? 'bg-bad/15 text-bad' : 'bg-accent/15 text-indigo-300',
            )}
          >
            {danger ? <AlertTriangle className="h-5 w-5" aria-hidden="true" /> : <Circle className="h-5 w-5" aria-hidden="true" />}
          </span>
          <div className="min-w-0 text-sm text-ink2">{message}</div>
        </div>
        {requireText !== undefined ? (
          <div className="space-y-1.5">
            <p className="text-xs text-ink2">
              Type{' '}
              <code className="rounded bg-page px-1.5 py-0.5 font-mono text-xs text-warn">{requireText}</code>{' '}
              to confirm.
            </p>
            <Input
              value={typed}
              onChange={(event) => setTyped(event.target.value)}
              placeholder={requireText}
              autoComplete="off"
              aria-label={`Type ${requireText} to confirm`}
            />
          </div>
        ) : null}
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Table (generic)
// ---------------------------------------------------------------------------

export interface TableColumn<T> {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  align?: 'left' | 'right' | 'center';
  /** Enables click-to-sort; sorting applies to the current page. */
  sortValue?: (row: T) => string | number;
}

export interface TablePagination {
  page: number; // zero-based
  hasPrev: boolean;
  hasNext: boolean;
  onPrev: () => void;
  onNext: () => void;
  loadedCount: number;
  hint?: string;
}

interface TableProps<T> {
  columns: Array<TableColumn<T>>;
  rows: T[];
  rowKey: (row: T) => string;
  loading?: boolean;
  emptyTitle?: string;
  emptyMessage?: string;
  onRowClick?: (row: T) => void;
  pagination?: TablePagination;
}

function compareValues(a: string | number, b: string | number): number {
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  return String(a).localeCompare(String(b));
}

export function Table<T>({
  columns,
  rows,
  rowKey,
  loading = false,
  emptyTitle = 'No results',
  emptyMessage = 'Try adjusting your search or filters.',
  onRowClick,
  pagination,
}: TableProps<T>): JSX.Element {
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const sortColumn = sortKey !== null ? columns.find((col) => col.key === sortKey) : undefined;
  const sortValue = sortColumn?.sortValue;
  const sortedRows =
    sortValue !== undefined
      ? [...rows].sort((a, b) => (sortDir === 'asc' ? compareValues(sortValue(a), sortValue(b)) : -compareValues(sortValue(a), sortValue(b))))
      : rows;

  function toggleSort(key: string): void {
    if (sortKey === key) {
      if (sortDir === 'asc') setSortDir('desc');
      else {
        setSortKey(null);
        setSortDir('asc');
      }
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  }

  const alignClass = (align: 'left' | 'right' | 'center' | undefined): string =>
    align === 'right' ? 'text-right' : align === 'center' ? 'text-center' : 'text-left';

  return (
    <div className="overflow-hidden rounded-xl border border-edge bg-surface">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[640px] border-collapse text-left">
          <thead>
            <tr className="border-b border-edge bg-elevated/40">
              {columns.map((col) => (
                <th
                  key={col.key}
                  scope="col"
                  className={cn('whitespace-nowrap px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-ink2', alignClass(col.align))}
                >
                  {col.sortValue !== undefined ? (
                    <button
                      type="button"
                      onClick={() => toggleSort(col.key)}
                      className="inline-flex items-center gap-1 transition-colors hover:text-ink"
                      aria-label={`Sort by ${typeof col.header === 'string' ? col.header : col.key}`}
                    >
                      {col.header}
                      {sortKey === col.key ? (
                        sortDir === 'asc' ? (
                          <ArrowUp className="h-3 w-3" aria-hidden="true" />
                        ) : (
                          <ArrowDown className="h-3 w-3" aria-hidden="true" />
                        )
                      ) : (
                        <ArrowUpDown className="h-3 w-3 opacity-40" aria-hidden="true" />
                      )}
                    </button>
                  ) : (
                    col.header
                  )}
                </th>
              ))}
            </tr>
          </thead>
          {loading && sortedRows.length === 0 ? (
            <tbody aria-busy="true">
              {Array.from({ length: 6 }).map((_, rowIndex) => (
                <tr key={rowIndex} className="border-t border-edge">
                  {columns.map((col) => (
                    <td key={col.key} className="px-4 py-3.5">
                      <div className="h-4 animate-pulse rounded bg-elevated" />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          ) : !loading && sortedRows.length === 0 ? (
            <tbody>
              <tr>
                <td colSpan={columns.length} className="px-4 py-16 text-center">
                  <Inbox className="mx-auto h-8 w-8 text-ink2/50" aria-hidden="true" />
                  <p className="mt-3 text-sm font-medium text-ink">{emptyTitle}</p>
                  <p className="mt-1 text-xs text-ink2">{emptyMessage}</p>
                </td>
              </tr>
            </tbody>
          ) : (
            <tbody className={cn(loading && 'pointer-events-none opacity-50 transition-opacity')}>
              {sortedRows.map((row) => (
                <tr
                  key={rowKey(row)}
                  onClick={onRowClick !== undefined ? () => onRowClick(row) : undefined}
                  className={cn(
                    'border-t border-edge transition-colors hover:bg-elevated/40',
                    onRowClick !== undefined && 'cursor-pointer',
                  )}
                >
                  {columns.map((col) => (
                    <td key={col.key} className={cn('px-4 py-3 text-sm text-ink', alignClass(col.align))}>
                      {col.render(row)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          )}
        </table>
      </div>
      {pagination !== undefined ? (
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-edge px-4 py-3">
          <p className="text-xs text-ink2">
            Page {pagination.page + 1}
            {pagination.loadedCount > 0 ? ` · ${pagination.loadedCount} rows` : ''}
            {pagination.hint !== undefined ? ` · ${pagination.hint}` : ''}
          </p>
          <div className="flex items-center gap-2">
            <Button variant="secondary" size="sm" disabled={!pagination.hasPrev || loading} onClick={pagination.onPrev}>
              <ChevronLeft className="h-3.5 w-3.5" aria-hidden="true" />
              Prev
            </Button>
            <Button variant="secondary" size="sm" disabled={!pagination.hasNext || loading} onClick={pagination.onNext}>
              Next
              <ChevronRight className="h-3.5 w-3.5" aria-hidden="true" />
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------------------
// PageHeader
// ---------------------------------------------------------------------------

interface PageHeaderProps {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}

export function PageHeader({ title, description, actions }: PageHeaderProps): JSX.Element {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-xl font-bold tracking-tight text-ink">{title}</h1>
        {description !== undefined ? <p className="mt-1 max-w-2xl text-sm text-ink2">{description}</p> : null}
      </div>
      {actions !== undefined ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Error / empty helpers
// ---------------------------------------------------------------------------

export function ErrorNotice({ error, onRetry }: { error: ApiError; onRetry?: () => void }): JSX.Element {
  return (
    <div className="rounded-xl border border-bad/40 bg-bad/10 p-4" role="alert">
      <div className="flex flex-wrap items-start gap-3">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-bad" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-bad">{error.message}</p>
          <p className="mt-1 font-mono text-[11px] text-ink2">
            code: {error.code} · req: {error.requestId}
          </p>
        </div>
        {onRetry !== undefined ? (
          <Button variant="secondary" size="sm" onClick={onRetry}>
            <ArrowLeft className="h-3.5 w-3.5 rotate-180" aria-hidden="true" />
            Retry
          </Button>
        ) : null}
      </div>
    </div>
  );
}

export function Skeleton({ className }: { className?: string }): JSX.Element {
  return <div className={cn('animate-pulse rounded-lg bg-elevated', className)} />;
}

// ---------------------------------------------------------------------------
// Toast system
// ---------------------------------------------------------------------------

interface ToastItem {
  id: number;
  kind: 'success' | 'error';
  message: string;
  requestId?: string;
}

export interface ToastApi {
  success: (message: string) => void;
  error: (message: string, requestId?: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (ctx === null) throw new Error('useToast must be used within ToastProvider');
  return ctx;
}

export function ToastProvider({ children }: { children: ReactNode }): JSX.Element {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const counter = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const push = useCallback(
    (kind: 'success' | 'error', message: string, requestId?: string) => {
      counter.current += 1;
      const id = counter.current;
      setToasts((current) => [...current.slice(-3), { id, kind, message, requestId }]);
      window.setTimeout(() => dismiss(id), kind === 'error' ? 6500 : 4000);
    },
    [dismiss],
  );

  const api = useMemo<ToastApi>(
    () => ({
      success: (message) => push('success', message),
      error: (message, requestId) => push('error', message, requestId),
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-[100] flex w-full max-w-sm flex-col gap-2">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            role="status"
            aria-live={toast.kind === 'error' ? 'assertive' : 'polite'}
            className={cn(
              'pointer-events-auto rounded-xl border bg-surface/95 p-4 shadow-lg backdrop-blur',
              toast.kind === 'success' ? 'border-ok/40' : 'border-bad/40',
            )}
          >
            <div className="flex items-start gap-2.5">
              {toast.kind === 'success' ? (
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-ok" aria-hidden="true" />
              ) : (
                <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-bad" aria-hidden="true" />
              )}
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-ink">{toast.message}</p>
                {toast.requestId !== undefined && toast.requestId !== '' ? (
                  <p className="mt-1 font-mono text-[11px] text-ink2">req: {toast.requestId}</p>
                ) : null}
              </div>
              <button
                type="button"
                onClick={() => dismiss(toast.id)}
                aria-label="Dismiss notification"
                className="rounded p-0.5 text-ink2 transition-colors hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
              >
                <X className="h-3.5 w-3.5" aria-hidden="true" />
              </button>
            </div>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

/** Convenience: run an async mutation with toast feedback (never silent-fail). */
export async function runWithToast(
  task: () => Promise<void>,
  toast: ToastApi,
  successMessage: string,
): Promise<boolean> {
  try {
    await task();
    toast.success(successMessage);
    return true;
  } catch (err) {
    const apiError = toApiError(err);
    toast.error(apiError.message, apiError.requestId);
    return false;
  }
}
