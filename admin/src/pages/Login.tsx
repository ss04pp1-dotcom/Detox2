/**
 * Admin login. The API returns 401 with a requestId for bad credentials —
 * surfaced here so credential stuffing attempts are traceable.
 */
import { useState, type FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Loader2, Shield } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { IS_MOCK } from '../api/client';
import { ApiError, toApiError } from '../api/error';
import { Button, ErrorNotice, Field, Input } from '../components/ui';

export default function LoginPage(): JSX.Element {
  const { admin, loading, login } = useAuth();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  // Keep the original ApiError (code/message/requestId) instead of relabeling
  // every failure as UNAUTHORIZED — network/5xx errors keep their real code.
  const [apiError, setApiError] = useState<ApiError | null>(null);

  if (!loading && admin !== null) {
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  const onSubmit = async (event: FormEvent): Promise<void> => {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setApiError(null);
    try {
      await login(email.trim(), password);
    } catch (err) {
      setApiError(toApiError(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-page px-4 text-ink">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center gap-3 text-center">
          <span className="flex h-14 w-14 items-center justify-center rounded-2xl bg-accent/15 text-accent">
            <Shield className="h-7 w-7" aria-hidden="true" />
          </span>
          <div>
            <h1 className="text-xl font-bold tracking-tight">MAXLEVEL DETOX</h1>
            <p className="text-xs font-semibold uppercase tracking-widest text-ink2">Admin Control Center</p>
          </div>
        </div>

        <form onSubmit={onSubmit} className="rounded-2xl border border-edge bg-surface p-6">
          {apiError !== null ? (
            <div className="mb-4">
              <ErrorNotice error={apiError} />
            </div>
          ) : null}

          <div className="space-y-4">
            <Field label="Email" htmlFor="admin-email">
              <Input
                id="admin-email"
                type="email"
                autoComplete="username"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="admin@maxleveldetox.com"
              />
            </Field>

            <Field label="Password" htmlFor="admin-password">
              <Input
                id="admin-password"
                type="password"
                autoComplete="current-password"
                required
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••••••"
              />
            </Field>
          </div>

          <Button type="submit" className="mt-6 w-full" disabled={submitting}>
            {submitting ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : null}
            {submitting ? 'Signing in…' : 'Sign in'}
          </Button>

          {IS_MOCK ? (
            <p className="mt-4 rounded-lg border border-warn/30 bg-warn/10 p-3 text-xs text-warn">
              <strong>Mock mode</strong> — any admin email with password <code>demo1234</code> signs in.
            </p>
          ) : null}
        </form>

        <p className="mt-6 text-center text-xs text-ink2">
          Admin authentication is separate from app users. Cloudflare Access with MFA is recommended in production.
        </p>
      </div>
    </div>
  );
}
