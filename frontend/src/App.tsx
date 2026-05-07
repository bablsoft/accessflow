import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { App as AntdApp } from 'antd';
import { AuthGuard } from '@/components/common/AuthGuard';
import { AppLayout } from '@/layouts/AppLayout';
import { LoginPage } from '@/pages/auth/LoginPage';
import { SetupPage } from '@/pages/auth/SetupPage';
import { useSetupStore } from '@/store/setupStore';
import { QueryEditorPage } from '@/pages/editor/QueryEditorPage';
import { QueryListPage } from '@/pages/queries/QueryListPage';
import { QueryDetailPage } from '@/pages/queries/QueryDetailPage';
import { ReviewQueuePage } from '@/pages/reviews/ReviewQueuePage';
import { DatasourceListPage } from '@/pages/datasources/DatasourceListPage';
import { DatasourceSettingsPage } from '@/pages/datasources/DatasourceSettingsPage';
import { UsersPage } from '@/pages/admin/UsersPage';
import { AuditLogPage } from '@/pages/admin/AuditLogPage';
import { AIConfigPage } from '@/pages/admin/AIConfigPage';
import { NotificationsPage } from '@/pages/admin/NotificationsPage';
import { ReviewPlansPage } from '@/pages/admin/ReviewPlansPage';
import { SamlConfigPage } from '@/pages/admin/SamlConfigPage';
import { usePreferencesStore } from '@/store/preferencesStore';

export function App() {
  const setupRequired = useSetupStore((s) => s.setupRequired);
  const location = useLocation();
  const edition = usePreferencesStore((s) => s.edition);

  if (setupRequired === true && location.pathname !== '/setup') {
    return <Navigate to="/setup" replace />;
  }
  if (setupRequired === false && location.pathname === '/setup') {
    return <Navigate to="/login" replace />;
  }

  return (
    <AntdApp>
      <Routes>
        <Route path="/setup" element={<SetupPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <AuthGuard>
              <AppLayout />
            </AuthGuard>
          }
        >
          <Route path="/" element={<Navigate to="/editor" replace />} />
          <Route path="/editor" element={<QueryEditorPage />} />
          <Route path="/queries" element={<QueryListPage />} />
          <Route path="/queries/:id" element={<QueryDetailPage />} />
          <Route
            path="/reviews"
            element={
              <AuthGuard requireRole={['REVIEWER', 'ADMIN']}>
                <ReviewQueuePage />
              </AuthGuard>
            }
          />
          <Route
            path="/datasources"
            element={
              <AuthGuard requireRole="ADMIN">
                <DatasourceListPage />
              </AuthGuard>
            }
          />
          <Route
            path="/datasources/:id/settings"
            element={
              <AuthGuard requireRole="ADMIN">
                <DatasourceSettingsPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/users"
            element={
              <AuthGuard requireRole="ADMIN">
                <UsersPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/review-plans"
            element={
              <AuthGuard requireRole="ADMIN">
                <ReviewPlansPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/audit-log"
            element={
              <AuthGuard requireRole="ADMIN">
                <AuditLogPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/ai-config"
            element={
              <AuthGuard requireRole="ADMIN">
                <AIConfigPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/notifications"
            element={
              <AuthGuard requireRole="ADMIN">
                <NotificationsPage />
              </AuthGuard>
            }
          />
          {edition === 'ENTERPRISE' && (
            <Route
              path="/admin/saml"
              element={
                <AuthGuard requireRole="ADMIN">
                  <SamlConfigPage />
                </AuthGuard>
              }
            />
          )}
        </Route>
        <Route path="*" element={<Navigate to="/editor" replace />} />
      </Routes>
    </AntdApp>
  );
}
