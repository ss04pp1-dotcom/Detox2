/**
 * App shell: permission-filtered sidebar + topbar (global search, mock badge,
 * admin identity, logout) around the routed content.
 */
import { useState, type ReactNode } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import {
  Activity,
  BarChart3,
  ChevronLeft,
  CreditCard,
  Crosshair,
  Flag,
  LayoutDashboard,
  LifeBuoy,
  LogOut,
  Megaphone,
  Package,
  ScrollText,
  Search,
  Server,
  Settings2,
  Shield,
  ShieldAlert,
  Smartphone,
  Tags,
  Trophy,
  UserCog,
  Users as UsersIcon,
  Wallet,
} from 'lucide-react';
import { IS_MOCK } from '../api/client';
import { ROLE_LABELS, useAuth } from '../auth/AuthContext';
import type { Permission } from '../api/types';
import { cn } from '../lib/format';

interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  permission: Permission;
  end?: boolean;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

const NAV: NavGroup[] = [
  {
    label: 'Overview',
    items: [
      { to: '/', label: 'Dashboard', icon: <LayoutDashboard className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_ANALYTICS', end: true },
      { to: '/live', label: 'Live Operations', icon: <Activity className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_ANALYTICS' },
    ],
  },
  {
    label: 'Users',
    items: [
      { to: '/users', label: 'Users', icon: <UsersIcon className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_USERS' },
      { to: '/leaderboard', label: 'Leaderboard & Clubs', icon: <Trophy className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_ANALYTICS' },
      { to: '/devices', label: 'Devices', icon: <Smartphone className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_DEVICES' },
      { to: '/subscriptions', label: 'Subscriptions', icon: <CreditCard className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_USERS' },
      { to: '/plans', label: 'Plans', icon: <Tags className="h-4 w-4" aria-hidden="true" />, permission: 'MANAGE_CONFIG' },
      { to: '/payments', label: 'bKash Payments', icon: <Wallet className="h-4 w-4" aria-hidden="true" />, permission: 'MANAGE_PAYMENTS' },
    ],
  },
  {
    label: 'Product',
    items: [
      { to: '/config', label: 'Remote Config', icon: <Settings2 className="h-4 w-4" aria-hidden="true" />, permission: 'MANAGE_CONFIG' },
      { to: '/detection-rules', label: 'Detection Rules', icon: <Crosshair className="h-4 w-4" aria-hidden="true" />, permission: 'MANAGE_CONFIG' },
      { to: '/flags', label: 'Feature Flags', icon: <Flag className="h-4 w-4" aria-hidden="true" />, permission: 'MANAGE_FLAGS' },
      { to: '/app-versions', label: 'App Versions', icon: <Package className="h-4 w-4" aria-hidden="true" />, permission: 'MANAGE_CONFIG' },
      { to: '/announcements', label: 'Announcements', icon: <Megaphone className="h-4 w-4" aria-hidden="true" />, permission: 'MANAGE_ANNOUNCEMENTS' },
    ],
  },
  {
    label: 'Analytics',
    items: [
      { to: '/analytics', label: 'Product Analytics', icon: <BarChart3 className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_ANALYTICS', end: true },
      { to: '/analytics/enforcement', label: 'Enforcement Analytics', icon: <ShieldAlert className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_ANALYTICS' },
      { to: '/analytics/revenue', label: 'Revenue', icon: <BarChart3 className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_ANALYTICS' },
    ],
  },
  {
    label: 'Support',
    items: [
      { to: '/support', label: 'Tickets', icon: <LifeBuoy className="h-4 w-4" aria-hidden="true" />, permission: 'MANAGE_SUPPORT' },
    ],
  },
  {
    label: 'Security',
    items: [
      { to: '/security', label: 'Security Events', icon: <ShieldAlert className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_AUDIT' },
      { to: '/audit', label: 'Audit Logs', icon: <ScrollText className="h-4 w-4" aria-hidden="true" />, permission: 'VIEW_AUDIT' },
    ],
  },
  {
    label: 'System',
    items: [
      { to: '/system', label: 'API Health', icon: <Server className="h-4 w-4" aria-hidden="true" />, permission: 'MANAGE_SYSTEM' },
      { to: '/system/admins', label: 'Admin Users', icon: <UserCog className="h-4 w-4" aria-hidden="true" />, permission: 'MANAGE_SYSTEM' },
    ],
  },
];

export default function Layout(): JSX.Element {
  const { admin, logout, hasPermission } = useAuth();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [collapsed, setCollapsed] = useState(false);

  const visibleNav = NAV.map((group) => ({
    ...group,
    items: group.items.filter((item) => hasPermission(item.permission)),
  })).filter((group) => group.items.length > 0);

  const onSearch = (event: React.FormEvent): void => {
    event.preventDefault();
    const q = search.trim();
    if (q.length === 0) return;
    navigate(`/users?q=${encodeURIComponent(q)}`);
    setSearch('');
  };

  return (
    <div className="flex min-h-screen bg-page text-ink">
      {/* Sidebar */}
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-30 flex flex-col border-r border-edge/80 bg-surface/90 shadow-2xl shadow-black/30 backdrop-blur-xl transition-[width] duration-200',
          collapsed ? 'w-16' : 'w-60',
        )}
      >
        <div className="flex h-14 items-center gap-2.5 border-b border-edge px-4">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-accent/15 text-accent">
            <Shield className="h-4.5 w-4.5 h-[18px] w-[18px]" aria-hidden="true" />
          </span>
          {!collapsed ? (
            <div className="min-w-0">
              <p className="truncate text-sm font-bold leading-tight">MAXLEVEL DETOX</p>
              <p className="text-[10px] font-semibold uppercase tracking-widest text-accent">Admin</p>
            </div>
          ) : null}
        </div>

        <nav className="flex-1 overflow-y-auto px-2 py-3" aria-label="Main navigation">
          {visibleNav.map((group) => (
            <div key={group.label} className="mb-4">
              {!collapsed ? (
                <p className="px-2 pb-1.5 text-[10px] font-bold uppercase tracking-widest text-ink2">{group.label}</p>
              ) : null}
              <ul className="space-y-0.5">
                {group.items.map((item) => (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      end={item.end}
                      title={collapsed ? item.label : undefined}
                      className={({ isActive }) =>
                        cn(
                          'flex items-center gap-2.5 rounded-xl border border-transparent px-2.5 py-2.5 text-sm font-medium transition-all duration-200',
                          isActive
                            ? 'border-accent/20 bg-accent/[0.12] text-accent shadow-[0_8px_24px_-16px_rgba(110,168,255,.8)]'
                            : 'text-ink2 hover:border-edge hover:bg-elevated/80 hover:text-ink',
                        )
                      }
                    >
                      {item.icon}
                      {!collapsed ? <span className="truncate">{item.label}</span> : null}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>

        <button
          type="button"
          onClick={() => setCollapsed((c) => !c)}
          className="flex items-center gap-2 border-t border-edge px-4 py-3 text-xs font-medium text-ink2 hover:text-ink"
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          <ChevronLeft className={cn('h-4 w-4 transition-transform', collapsed && 'rotate-180')} aria-hidden="true" />
          {!collapsed ? 'Collapse' : null}
        </button>
      </aside>

      {/* Main column */}
      <div className={cn('flex min-h-screen w-full flex-col transition-[padding] duration-200', collapsed ? 'pl-16' : 'pl-60')}>
        {/* Topbar */}
        <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-edge bg-surface/95 px-4 backdrop-blur">
          <form onSubmit={onSearch} className="relative ml-auto w-full max-w-sm" role="search">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink2" aria-hidden="true" />
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search users by email…"
              aria-label="Search users by email"
              className="w-full rounded-lg border border-edge bg-page py-2 pl-9 pr-3 text-sm text-ink placeholder:text-ink2 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </form>

          {IS_MOCK ? (
            <span className="rounded-md border border-warn/40 bg-warn/10 px-2 py-1 text-[10px] font-bold uppercase tracking-wider text-warn">
              Mock data
            </span>
          ) : null}

          {admin !== null ? (
            <div className="flex items-center gap-3 border-l border-edge pl-3">
              <div className="hidden text-right sm:block">
                <p className="text-sm font-semibold leading-tight">{admin.email}</p>
                <p className="text-[11px] text-ink2">{ROLE_LABELS[admin.role]}</p>
              </div>
              <button
                type="button"
                onClick={() => {
                  logout();
                  navigate('/login');
                }}
                className="flex items-center gap-1.5 rounded-lg border border-edge px-2.5 py-1.5 text-xs font-medium text-ink2 hover:border-bad/40 hover:text-bad"
                aria-label="Log out"
              >
                <LogOut className="h-3.5 w-3.5" aria-hidden="true" />
                <span className="hidden sm:inline">Logout</span>
              </button>
            </div>
          ) : null}
        </header>

        {/* Content */}
        <main className="mld-page-glow flex-1 px-4 py-6 sm:px-6 lg:px-8">
          <div className="mx-auto w-full max-w-7xl">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
}
