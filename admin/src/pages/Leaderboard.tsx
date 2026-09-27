/**
 * Leaderboard & Clubs (v2.5.8 roadmap) — community moderation surface.
 *
 * Two sections:
 *  - The DP leaderboard: opted-in stats + top boards per window, with a
 *    confirm-gated profile reset (wipes a farmed standing and forces
 *    opt-out — the device stays the DP authority).
 *  - Clubs: paginated list with member counts and hide/restore/delete.
 *    Hiding makes a club invisible + unjoinable; deleting removes it and
 *    every membership.
 *
 * Privacy guardrails mirrored in the UI: anonymous-mode users render as
 * their pseudonym; the board never shows more than the app contract does.
 */
import { useMemo, useState } from 'react';
import { EyeOff, RotateCcw, Trophy, Users, Eye, Trash2 } from 'lucide-react';
import { api } from '../api/client';
import type { Club, LeaderboardEntry, LeaderboardWindow } from '../api/types';
import { useApiData, useDebounced } from '../lib/hooks';
import {
  Badge,
  Button,
  Card,
  ConfirmModal,
  ErrorNotice,
  Input,
  PageHeader,
  Skeleton,
  StatCard,
  Table,
  Textarea,
  useToast,
  type TableColumn,
} from '../components/ui';
import { timeAgo } from '../lib/format';

function rid(err: unknown): string | undefined {
  if (err && typeof err === 'object' && 'requestId' in err) {
    return (err as { requestId?: string }).requestId;
  }
  return undefined;
}

export default function LeaderboardPage(): JSX.Element {
  const toast = useToast();

  // ---- leaderboard state ----
  const [window, setWindow] = useState<LeaderboardWindow>('alltime');
  const [boardSearch, setBoardSearch] = useState('');
  const boardQuery = useDebounced(boardSearch, 350);
  const {
    data: board,
    loading: boardLoading,
    error: boardError,
    reload: reloadBoard,
  } = useApiData(
    () => api.getLeaderboard({ window, q: boardQuery.trim() || undefined }),
    [window, boardQuery],
  );

  const [resetTarget, setResetTarget] = useState<LeaderboardEntry | null>(null);
  const [resetReason, setResetReason] = useState('');
  const [resetBusy, setResetBusy] = useState(false);

  // ---- clubs state ----
  const [clubFilter, setClubFilter] = useState<'visible' | 'hidden' | 'all'>('visible');
  const [clubSearch, setClubSearch] = useState('');
  const clubQuery = useDebounced(clubSearch, 350);
  const [clubPage, setClubPage] = useState(1);
  const {
    data: clubs,
    loading: clubsLoading,
    error: clubsError,
    reload: reloadClubs,
  } = useApiData(
    () => api.listClubs({ filter: clubFilter, q: clubQuery.trim() || undefined, page: clubPage }),
    [clubFilter, clubQuery, clubPage],
  );

  const [clubAction, setClubAction] = useState<{ club: Club; kind: 'hide' | 'restore' | 'delete' } | null>(null);
  const [clubBusy, setClubBusy] = useState(false);

  const runReset = async (): Promise<void> => {
    if (resetTarget === null) return;
    const reason = resetReason.trim();
    if (reason.length < 3) {
      toast.error('A reason of at least 3 characters is required.');
      return;
    }
    setResetBusy(true);
    try {
      await api.resetLeaderboardProfile(resetTarget.userId, reason);
      toast.success(`Profile reset — ${resetTarget.displayName} is off the board.`);
      setResetTarget(null);
      setResetReason('');
      reloadBoard();
    } catch (err) {
      toast.error('Reset failed', rid(err));
    } finally {
      setResetBusy(false);
    }
  };

  const runClubAction = async (): Promise<void> => {
    if (clubAction === null) return;
    setClubBusy(true);
    try {
      const { club, kind } = clubAction;
      if (kind === 'hide') await api.hideClub(club.id);
      else if (kind === 'restore') await api.restoreClub(club.id);
      else await api.deleteClub(club.id);
      toast.success(
        kind === 'hide'
          ? `Club "${club.name}" hidden — invisible and unjoinable.`
          : kind === 'restore'
            ? `Club "${club.name}" restored.`
            : `Club "${club.name}" deleted with all memberships.`,
      );
      setClubAction(null);
      reloadClubs();
      reloadBoard(); // hidden-club totals feed the stat cards
    } catch (err) {
      toast.error('Club action failed', rid(err));
    } finally {
      setClubBusy(false);
    }
  };

  const boardColumns: Array<TableColumn<LeaderboardEntry>> = useMemo(
    () => [
      { key: 'rank', header: '#', render: (e) => <span className="font-mono text-xs text-ink2">#{e.rank}</span> },
      {
        key: 'player',
        header: 'Player',
        render: (e) => (
          <div>
            <p className="font-medium text-ink">{e.displayName}</p>
            <p className="text-xs text-ink2">
              {e.displayMode === 'anonymous' ? 'anonymous mode' : e.email}
            </p>
          </div>
        ),
      },
      {
        key: 'level',
        header: 'Level',
        render: (e) => <Badge tone="accent">{e.levelName ?? '—'}</Badge>,
      },
      { key: 'streak', header: 'Streak', render: (e) => <span className="text-ink2">{e.streakDays} d</span> },
      { key: 'value', header: window === 'alltime' ? 'Lifetime DP' : window === 'weekly' ? 'Week DP' : 'Month DP', render: (e) => <span className="font-semibold text-ink">{e.value.toLocaleString()}</span> },
      { key: 'status', header: 'User', render: (e) => <Badge tone={e.userStatus === 'ACTIVE' ? 'success' : 'warning'}>{e.userStatus}</Badge> },
      { key: 'sync', header: 'Synced', render: (e) => <span className="text-ink2">{timeAgo(e.updatedAt)}</span> },
      {
        key: 'actions',
        header: '',
        render: (e) => (
          <Button variant="ghost" onClick={() => { setResetTarget(e); setResetReason(''); }} title="Reset profile">
            <RotateCcw className="h-4 w-4" aria-hidden="true" />
          </Button>
        ),
      },
    ],
    [window],
  );

  const clubColumns: Array<TableColumn<Club>> = useMemo(
    () => [
      {
        key: 'club',
        header: 'Club',
        render: (c) => (
          <div>
            <p className="font-medium text-ink">{c.name}</p>
            <p className="text-xs text-ink2">{c.description ?? 'No description'}</p>
          </div>
        ),
      },
      { key: 'code', header: 'Invite', render: (c) => <span className="font-mono text-xs text-ink2">{c.inviteCode}</span> },
      { key: 'members', header: 'Members', render: (c) => <span className="text-ink2">{c.memberCount}</span> },
      { key: 'creator', header: 'Created by', render: (c) => <span className="text-ink2">{c.creatorName ?? c.createdBy}</span> },
      { key: 'created', header: 'Created', render: (c) => <span className="text-ink2">{timeAgo(c.createdAt)}</span> },
      {
        key: 'state',
        header: 'State',
        render: (c) =>
          c.hidden ? <Badge tone="danger">hidden</Badge> : <Badge tone="success">visible</Badge>,
      },
      {
        key: 'actions',
        header: '',
        render: (c) => (
          <div className="flex justify-end gap-1">
            {c.hidden ? (
              <Button variant="ghost" title="Restore" onClick={() => setClubAction({ club: c, kind: 'restore' })}>
                <Eye className="h-4 w-4" aria-hidden="true" />
              </Button>
            ) : (
              <Button variant="ghost" title="Hide" onClick={() => setClubAction({ club: c, kind: 'hide' })}>
                <EyeOff className="h-4 w-4" aria-hidden="true" />
              </Button>
            )}
            <Button variant="ghost" title="Delete" onClick={() => setClubAction({ club: c, kind: 'delete' })}>
              <Trash2 className="h-4 w-4 text-bad" aria-hidden="true" />
            </Button>
          </div>
        ),
      },
    ],
    [],
  );

  const totals = board?.totals;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Leaderboard & Clubs"
        description="The v2.5.8 community layer: opt-in DP ranking and invite-code clubs. Participation is opt-in and anonymous mode is honored everywhere; moderation actions are audited."
      />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Opted-in players" value={totals ? totals.optedIn.toLocaleString() : '—'} icon={<Trophy className="h-4 w-4" aria-hidden="true" />} loading={boardLoading && totals === undefined} />
        <StatCard label="Mirrored lifetime DP" value={totals ? totals.lifetimeDp.toLocaleString() : '—'} loading={boardLoading && totals === undefined} />
        <StatCard label="Clubs" value={totals ? totals.clubs.toLocaleString() : '—'} icon={<Users className="h-4 w-4" aria-hidden="true" />} loading={boardLoading && totals === undefined} />
        <StatCard label="Hidden clubs" value={totals ? totals.hiddenClubs.toLocaleString() : '—'} accent="bad" loading={boardLoading && totals === undefined} />
      </div>

      {/* ---- Leaderboard ---- */}
      <Card padded={false}>
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-edge p-4">
          <div>
            <h2 className="text-sm font-semibold text-ink">DP Leaderboard</h2>
            <p className="text-xs text-ink2">Top 50 per window. Lifetime DP is monotonic per device — resets need moderation.</p>
          </div>
          <div className="flex items-center gap-2">
            {(['weekly', 'monthly', 'alltime'] as const).map((w) => (
              <Button key={w} variant={window === w ? 'primary' : 'ghost'} onClick={() => setWindow(w)}>
                {w === 'weekly' ? 'Week' : w === 'monthly' ? 'Month' : 'All-time'}
              </Button>
            ))}
          </div>
        </div>
        <div className="border-b border-edge p-4">
          <Input
            type="search"
            value={boardSearch}
            onChange={(e) => setBoardSearch(e.target.value)}
            placeholder="Search display name or user id…"
            aria-label="Search leaderboard"
          />
        </div>
        {boardError !== null ? <div className="p-4"><ErrorNotice error={boardError} onRetry={reloadBoard} /></div> : null}
        {boardError === null && (boardLoading && board === null) ? (
          <div className="space-y-2 p-4">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : null}
        {boardError === null && board !== null ? (
          <Table
            columns={boardColumns}
            rows={board.entries}
            rowKey={(e) => `${e.userId}-${e.rank}`}
            loading={false}
            emptyTitle="No ranked players"
            emptyMessage="Nobody has opted in for this window yet."
          />
        ) : null}
      </Card>

      {/* ---- Clubs ---- */}
      <Card padded={false}>
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-edge p-4">
          <div>
            <h2 className="text-sm font-semibold text-ink">Clubs</h2>
            <p className="text-xs text-ink2">Invite-code groups. Hide to make a club invisible and unjoinable; delete removes it entirely.</p>
          </div>
          <div className="flex items-center gap-2">
            {(['visible', 'hidden', 'all'] as const).map((f) => (
              <Button key={f} variant={clubFilter === f ? 'primary' : 'ghost'} onClick={() => { setClubFilter(f); setClubPage(1); }}>
                {f === 'visible' ? 'Visible' : f === 'hidden' ? 'Hidden' : 'All'}
              </Button>
            ))}
          </div>
        </div>
        <div className="border-b border-edge p-4">
          <Input
            type="search"
            value={clubSearch}
            onChange={(e) => { setClubSearch(e.target.value); setClubPage(1); }}
            placeholder="Search clubs by name…"
            aria-label="Search clubs"
          />
        </div>
        {clubsError !== null ? <div className="p-4"><ErrorNotice error={clubsError} onRetry={reloadClubs} /></div> : null}
        {clubsError === null && (clubsLoading && clubs === null) ? (
          <div className="space-y-2 p-4">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : null}
        {clubsError === null && clubs !== null ? (
          <Table
            columns={clubColumns}
            rows={clubs.clubs}
            rowKey={(c) => c.id}
            loading={false}
            emptyTitle="No clubs"
            emptyMessage="Clubs appear here as users create them."
            pagination={{
              page: clubs.page,
              hasPrev: clubs.page > 1,
              hasNext: clubs.page * clubs.pageSize < clubs.total,
              onPrev: () => setClubPage((p) => Math.max(1, p - 1)),
              onNext: () => setClubPage((p) => p + 1),
              loadedCount: clubs.clubs.length,
            }}
          />
        ) : null}
      </Card>

      {/* ---- Reset confirm (typed reason) ---- */}
      <ConfirmModal
        open={resetTarget !== null}
        title="Reset leaderboard profile?"
        message={
          resetTarget === null ? null : (
            <div className="space-y-3">
              <p>
                This zeroes <span className="font-medium text-ink">{resetTarget.displayName}</span> ({resetTarget.lifetimeDp.toLocaleString()} lifetime DP) and removes them from every board. The user can opt back in and re-sync from their device. The device stays the DP authority.
              </p>
              <Textarea
                value={resetReason}
                onChange={(e) => setResetReason(e.target.value)}
                placeholder="Moderation reason (audited, min 3 chars) — e.g. 'DP farming from emulated device'"
                aria-label="Moderation reason"
                rows={3}
              />
            </div>
          )
        }
        confirmLabel="Reset profile"
        danger
        loading={resetBusy}
        onConfirm={runReset}
        onCancel={() => setResetTarget(null)}
      />

      {/* ---- Club action confirms ---- */}
      <ConfirmModal
        open={clubAction !== null}
        title={
          clubAction?.kind === 'hide'
            ? 'Hide this club?'
            : clubAction?.kind === 'restore'
              ? 'Restore this club?'
              : 'Delete this club?'
        }
        message={
          clubAction === null ? null : clubAction.kind === 'hide' ? (
            <p>
              <span className="font-medium text-ink">{clubAction.club.name}</span> becomes invisible in search and
              unjoinable. Existing members keep their DP; the club leaderboard slice just stops being reachable. Reversible.
            </p>
          ) : clubAction.kind === 'restore' ? (
            <p>
              <span className="font-medium text-ink">{clubAction.club.name}</span> becomes searchable and joinable again.
            </p>
          ) : (
            <p>
              Permanently delete <span className="font-medium text-ink">{clubAction.club.name}</span> ({clubAction.club.memberCount} members)?
              All memberships are removed; members simply find themselves clubless. This cannot be undone.
            </p>
          )
        }
        confirmLabel={clubAction?.kind === 'hide' ? 'Hide club' : clubAction?.kind === 'restore' ? 'Restore club' : 'Delete club'}
        danger={clubAction?.kind === 'delete'}
        requireText={clubAction?.kind === 'delete' ? 'DELETE' : undefined}
        loading={clubBusy}
        onConfirm={runClubAction}
        onCancel={() => setClubAction(null)}
      />
    </div>
  );
}
