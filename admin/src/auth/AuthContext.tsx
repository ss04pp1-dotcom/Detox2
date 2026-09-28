/**
 * Admin authentication + RBAC.
 * - login() -> POST /admin/auth/login (token kept in memory + sessionStorage).
 * - Role -> derived permission set from the frozen RBAC matrix.
 * - RequireAuth guards the route tree; a 401 anywhere force-redirects to /login.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { api, onUnauthorized, setToken } from '../api/client';
import type { AdminRole, AdminUser, Permission } from '../api/types';

// ---------------------------------------------------------------------------
// Frozen RBAC matrix
// ---------------------------------------------------------------------------

export const ALL_PERMISSIONS: readonly Permission[] = [
  'VIEW_USERS',
  'EDIT_USERS',
  'VIEW_DEVICES',
  'MANAGE_CONFIG',
  'MANAGE_FLAGS',
  'MANAGE_ANNOUNCEMENTS',
  'VIEW_ANALYTICS',
  'VIEW_AUDIT',
  'MANAGE_SUPPORT',
  'MANAGE_PAYMENTS',
  'MANAGE_SYSTEM',
];

export const ROLE_PERMISSIONS: Record<AdminRole, readonly Permission[]> = {
  SUPER_ADMIN: ALL_PERMISSIONS,
  ADMIN: ALL_PERMISSIONS.filter((p) => p !== 'MANAGE_SYSTEM'),
  SUPPORT: ['VIEW_USERS', 'EDIT_USERS', 'VIEW_DEVICES', 'VIEW_AUDIT', 'MANAGE_SUPPORT', 'MANAGE_PAYMENTS'],
  ANALYST: ['VIEW_USERS', 'VIEW_DEVICES', 'VIEW_ANALYTICS', 'VIEW_AUDIT'],
  CONFIG_MANAGER: ['VIEW_USERS', 'VIEW_DEVICES', 'MANAGE_CONFIG', 'MANAGE_FLAGS', 'MANAGE_ANNOUNCEMENTS'],
  READ_ONLY: ['VIEW_USERS', 'VIEW_DEVICES', 'VIEW_ANALYTICS', 'VIEW_AUDIT'],
};

export const ROLE_LABELS: Record<AdminRole, string> = {
  SUPER_ADMIN: 'Super Admin',
  ADMIN: 'Admin',
  SUPPORT: 'Support',
  ANALYST: 'Analyst',
  CONFIG_MANAGER: 'Config Manager',
  READ_ONLY: 'Read Only',
};

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

interface AuthContextValue {
  admin: AdminUser | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  hasPermission: (permission: Permission) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const SESSION_KEY = 'mld_admin_session';

/**
 * v2.5.7 (A-3): idle auto-logout window. The KV session lives 8h; an
 * unattended admin tab should not keep a live token in sessionStorage for
 * that whole window. 30 minutes of inactivity clears the local session
 * (the server-side token simply expires on its own).
 */
const IDLE_LOGOUT_MS = 30 * 60 * 1000;

export function AuthProvider({ children }: { children: ReactNode }): JSX.Element {
  const [admin, setAdmin] = useState<AdminUser | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Restore session for this tab (sessionStorage only — no persistent auth).
    try {
      const raw = sessionStorage.getItem(SESSION_KEY);
      if (raw !== null) setAdmin(JSON.parse(raw) as AdminUser);
    } catch {
      sessionStorage.removeItem(SESSION_KEY);
    }
    setLoading(false);

    // Any 401 from the API client clears state and forces a clean re-login.
    onUnauthorized(() => {
      sessionStorage.removeItem(SESSION_KEY);
      setAdmin(null);
      if (window.location.pathname !== '/login') window.location.replace('/login');
    });

    // v2.5.7 (A-3): idle auto-logout — any interaction (pointer/keydown/
    // click/scroll) refreshes the deadline; total inactivity past the
    // window clears the session locally.
    let lastActivity = Date.now();
    const markActive = (): void => {
      lastActivity = Date.now();
    };
    const events: Array<keyof WindowEventMap> = ['pointerdown', 'keydown', 'click', 'scroll'];
    events.forEach((ev) => window.addEventListener(ev, markActive, { passive: true }));
    const idleTimer = window.setInterval(() => {
      if (Date.now() - lastActivity > IDLE_LOGOUT_MS) {
        sessionStorage.removeItem(SESSION_KEY);
        setToken(null);
        setAdmin(null);
      }
    }, 60_000);

    return () => {
      events.forEach((ev) => window.removeEventListener(ev, markActive));
      window.clearInterval(idleTimer);
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const response = await api.login(email, password);
    setToken(response.token);
    setAdmin(response.admin);
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(response.admin));
  }, []);

  const logout = useCallback(() => {
    // v2.5.7 (C-2): revoke the KV session SERVER-SIDE before clearing local
    // state. The worker route POST /admin/auth/logout (m22) existed all
    // along — the old comment below was stale and the call was missing, so
    // a stolen token stayed valid for up to 8 hours after "logout".
    // Fire-and-forget: the local clear must proceed even if the network
    // call fails (the server token then simply expires naturally).
    void api.logout().catch(() => undefined);
    setToken(null);
    sessionStorage.removeItem(SESSION_KEY);
    setAdmin(null);
  }, []);

  const hasPermission = useCallback((permission: Permission) => {
    if (admin === null) return false;
    // Guard against a restored session carrying an unmapped/legacy role —
    // ROLE_PERMISSIONS[role] would be undefined and .includes would throw.
    const perms: readonly Permission[] | undefined = ROLE_PERMISSIONS[admin.role];
    return perms !== undefined && perms.includes(permission);
  }, [admin]);

  const value = useMemo<AuthContextValue>(
    () => ({ admin, loading, login, logout, hasPermission }),
    [admin, loading, login, logout, hasPermission],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (ctx === null) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

// ---------------------------------------------------------------------------
// Route guard
// ---------------------------------------------------------------------------

export function RequireAuth({ children }: { children: JSX.Element }): JSX.Element {
  const { admin, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-page" role="status" aria-label="Loading session">
        <Loader2 className="h-6 w-6 animate-spin text-accent" aria-hidden="true" />
      </div>
    );
  }

  if (admin === null) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return children;
}
