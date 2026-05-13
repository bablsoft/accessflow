import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { App as AntdApp } from 'antd';
import { AuthGuard } from '@/components/common/AuthGuard';
import { AppLayout } from '@/layouts/AppLayout';
import { LoginPage } from '@/pages/auth/LoginPage';
import { SetupPage } from '@/pages/auth/SetupPage';
const OAuthCallbackPage = lazy(() => import('@/pages/auth/OAuthCallbackPage'));
const AcceptInvitePage = lazy(() => import('@/pages/auth/AcceptInvitePage'));
import { useSetupStore } from '@/store/setupStore';
import { QueryEditorPage } from '@/pages/editor/QueryEditorPage';
import { QueryListPage } from '@/pages/queries/QueryListPage';
import { QueryDetailPage } from '@/pages/queries/QueryDetailPage';
import { ReviewQueuePage } from '@/pages/reviews/ReviewQueuePage';
import { DatasourceListPage } from '@/pages/datasources/DatasourceListPage';
import { DatasourceSettingsPage } from '@/pages/datasources/DatasourceSettingsPage';

const DatasourceCreateWizardPage = lazy(
  () => import('@/pages/datasources/DatasourceCreateWizardPage'),
);
import { UsersPage } from '@/pages/admin/UsersPage';
import { AuditLogPage } from '@/pages/admin/AuditLogPage';
import { AiConfigListPage } from '@/pages/admin/ai-configs/AiConfigListPage';
const AiConfigCreateWizardPage = lazy(
  () => import('@/pages/admin/ai-configs/AiConfigCreateWizardPage'),
);
const AiConfigEditPage = lazy(() => import('@/pages/admin/ai-configs/AiConfigEditPage'));
import { NotificationsPage } from '@/pages/admin/NotificationsPage';
import { ReviewPlansPage } from '@/pages/admin/ReviewPlansPage';
import { SamlConfigPage } from '@/pages/admin/SamlConfigPage';
const OAuth2ConfigPage = lazy(() => import('@/pages/admin/OAuth2ConfigPage'));
import { LanguagesConfigPage } from '@/pages/admin/LanguagesConfigPage';
const CustomDriversPage = lazy(() => import('@/pages/admin/drivers/CustomDriversPage'));
import { ProfilePage } from '@/pages/profile/ProfilePage';

export function App() {
  const setupRequired = useSetupStore((s) => s.setupRequired);
  const location = useLocation();

  const isPublicPath =
    location.pathname === '/setup' || location.pathname.startsWith('/invite/');
  if (setupRequired === true && !isPublicPath) {
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
          path="/invite/:token"
          element={
            <Suspense fallback={null}>
              <AcceptInvitePage />
            </Suspense>
          }
        />
        <Route
          path="/auth/oauth/callback"
          element={
            <Suspense fallback={null}>
              <OAuthCallbackPage />
            </Suspense>
          }
        />
        <Route
          element={
            <AuthGuard>
              <AppLayout />
            </AuthGuard>
          }
        >
          <Route path="/" element={<Navigate to="/editor" replace />} />
          <Route path="/profile" element={<ProfilePage />} />
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
            path="/datasources/new"
            element={
              <AuthGuard requireRole="ADMIN">
                <Suspense fallback={null}>
                  <DatasourceCreateWizardPage />
                </Suspense>
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
            path="/admin/ai-configs"
            element={
              <AuthGuard requireRole="ADMIN">
                <AiConfigListPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/ai-configs/new"
            element={
              <AuthGuard requireRole="ADMIN">
                <Suspense fallback={null}>
                  <AiConfigCreateWizardPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/ai-configs/:id"
            element={
              <AuthGuard requireRole="ADMIN">
                <Suspense fallback={null}>
                  <AiConfigEditPage />
                </Suspense>
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
          <Route
            path="/admin/languages"
            element={
              <AuthGuard requireRole="ADMIN">
                <LanguagesConfigPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/drivers"
            element={
              <AuthGuard requireRole="ADMIN">
                <Suspense fallback={null}>
                  <CustomDriversPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/saml"
            element={
              <AuthGuard requireRole="ADMIN">
                <SamlConfigPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/oauth2"
            element={
              <AuthGuard requireRole="ADMIN">
                <Suspense fallback={null}>
                  <OAuth2ConfigPage />
                </Suspense>
              </AuthGuard>
            }
          />
        </Route>
        <Route path="*" element={<Navigate to="/editor" replace />} />
      </Routes>
    </AntdApp>
  );
}
