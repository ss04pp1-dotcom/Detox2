/**
 * Admin SPA routes. Every route except /login sits behind RequireAuth + Layout.
 * Individual pages gate their own data by permission (the API enforces RBAC
 * server-side regardless — the UI gating is UX, not security).
 */
import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/AuthContext';
import Layout from './components/Layout';
import LoginPage from './pages/Login';
import DashboardPage from './pages/Dashboard';
import { LiveOperationsPage } from './pages/Dashboard';
import { UsersPage, UserDetailPage } from './pages/Users';
import DevicesPage from './pages/Devices';
import SubscriptionsPage from './pages/Subscriptions';
import PlansPage from './pages/Plans';
import PaymentsPage from './pages/Payments';
import RemoteConfigPage from './pages/RemoteConfig';
import DetectionRulesPage from './pages/DetectionRules';
import LeaderboardPage from './pages/Leaderboard';
import FeatureFlagsPage from './pages/FeatureFlags';
import AppVersionsPage from './pages/AppVersions';
import AnnouncementsPage from './pages/Announcements';
import { ProductAnalyticsPage, EnforcementAnalyticsPage, RevenueAnalyticsPage } from './pages/Analytics';
import SupportPage from './pages/Support';
import SecurityEventsPage from './pages/SecurityEvents';
import AuditLogsPage from './pages/AuditLogs';
import { SystemPage, AdminUsersPage } from './pages/System';

export default function App(): JSX.Element {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route
        path="/"
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="live" element={<LiveOperationsPage />} />

        <Route path="users" element={<UsersPage />} />
        <Route path="users/:id" element={<UserDetailPage />} />
        <Route path="devices" element={<DevicesPage />} />
        <Route path="subscriptions" element={<SubscriptionsPage />} />

        {/* v2.2 Phase D — monetization surfaces. */}
        <Route path="plans" element={<PlansPage />} />
        <Route path="payments" element={<PaymentsPage />} />

        <Route path="config" element={<RemoteConfigPage />} />
        <Route path="detection-rules" element={<DetectionRulesPage />} />
        <Route path="leaderboard" element={<LeaderboardPage />} />
        <Route path="flags" element={<FeatureFlagsPage />} />
        <Route path="app-versions" element={<AppVersionsPage />} />
        <Route path="announcements" element={<AnnouncementsPage />} />

        <Route path="analytics" element={<ProductAnalyticsPage />} />
        <Route path="analytics/enforcement" element={<EnforcementAnalyticsPage />} />
        <Route path="analytics/revenue" element={<RevenueAnalyticsPage />} />

        <Route path="support" element={<SupportPage />} />

        <Route path="security" element={<SecurityEventsPage />} />
        <Route path="audit" element={<AuditLogsPage />} />

        <Route path="system" element={<SystemPage />} />
        <Route path="system/admins" element={<AdminUsersPage />} />

        <Route path="404" element={<NotFoundPage />} />
        <Route path="*" element={<Navigate to="/404" replace />} />
      </Route>
    </Routes>
  );
}

function NotFoundPage(): JSX.Element {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-3 text-center">
      <p className="text-6xl font-black tracking-tight text-ink2">404</p>
      <p className="text-lg font-semibold text-ink">This page does not exist.</p>
      <p className="text-sm text-ink2">Use the sidebar to navigate back to a known page.</p>
    </div>
  );
}
