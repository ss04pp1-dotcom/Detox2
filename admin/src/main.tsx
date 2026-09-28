import { Component, StrictMode, type ErrorInfo, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './auth/AuthContext';
import { ToastProvider } from './components/ui';
import './index.css';

/**
 * Top-level error boundary — keeps a render-time TypeError in any page from
 * unmounting the whole admin console (blank screen). Shows a recovery UI with
 * a reload action; the failing route's data errors are handled per-page.
 */
class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  override state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error): { error: Error | null } {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // eslint-disable-next-line no-console -- operators need the stack for reports.
    console.error('Unhandled admin console error', error, info.componentStack);
  }

  override render(): ReactNode {
    if (this.state.error !== null) {
      return (
        <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-page px-4 text-center text-ink">
          <h1 className="text-lg font-bold">Something went wrong</h1>
          <p className="max-w-md text-sm text-ink2">
            The admin console hit an unexpected error. Reloading usually recovers. If it persists, copy the browser
            console output (and the request ID from the failing action) when reporting.
          </p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="rounded-lg border border-transparent bg-accent px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-indigo-500"
          >
            Reload
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

const container = document.getElementById('root');
if (container === null) throw new Error('Missing #root element');

createRoot(container).render(
  <StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
        <AuthProvider>
          <ToastProvider>
            <App />
          </ToastProvider>
        </AuthProvider>
      </BrowserRouter>
    </ErrorBoundary>
  </StrictMode>,
);
