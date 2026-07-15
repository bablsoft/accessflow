import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { App as AntdApp } from 'antd';
import { AuthGuard } from '@/components/common/AuthGuard';
import { MessageBridgeBinder } from '@/components/common/MessageBridgeBinder';
import { NavigationBridgeBinder } from '@/components/common/NavigationBridgeBinder';
import { AppLayout } from '@/layouts/AppLayout';
import { LoginPage } from '@/pages/auth/LoginPage';
import { SetupPage } from '@/pages/auth/SetupPage';
const OAuthCallbackPage = lazy(() => import('@/pages/auth/OAuthCallbackPage'));
const SamlCallbackPage = lazy(() => import('@/pages/auth/SamlCallbackPage'));
const AcceptInvitePage = lazy(() => import('@/pages/auth/AcceptInvitePage'));
const ForgotPasswordPage = lazy(() => import('@/pages/auth/ForgotPasswordPage'));
const ResetPasswordPage = lazy(() => import('@/pages/auth/ResetPasswordPage'));
import { useSetupStore } from '@/store/setupStore';
import { useAuthStore } from '@/store/authStore';
import { resolveRouteGuard } from '@/utils/routeGuard';
import { homePathForUser } from '@/utils/homePath';
import { QueryEditorPage } from '@/pages/editor/QueryEditorPage';
const DashboardPage = lazy(() => import('@/pages/dashboard/DashboardPage'));
import { QueryListPage } from '@/pages/queries/QueryListPage';
import { QueryDetailPage } from '@/pages/queries/QueryDetailPage';
import { ReviewQueuePage } from '@/pages/reviews/ReviewQueuePage';
const PushDecidePage = lazy(() => import('@/pages/reviews/PushDecidePage'));
import { RequestAccessPage } from '@/pages/access-requests/RequestAccessPage';
import { AccessRequestsQueuePage } from '@/pages/access-requests/AccessRequestsQueuePage';
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
const AiAnalysesPage = lazy(() => import('@/pages/admin/AiAnalysesPage'));
const AnomaliesPage = lazy(() => import('@/pages/admin/AnomaliesPage'));
const BreakGlassLogPage = lazy(() => import('@/pages/admin/BreakGlassLogPage'));
const DataClassificationsPage = lazy(() => import('@/pages/admin/DataClassificationsPage'));
const DatasourceHealthPage = lazy(() => import('@/pages/admin/DatasourceHealthPage'));
import { NotificationsPage } from '@/pages/admin/NotificationsPage';
import { ReviewPlansPage } from '@/pages/admin/ReviewPlansPage';
import { RoutingPoliciesPage } from '@/pages/admin/RoutingPoliciesPage';
import { SamlConfigPage } from '@/pages/admin/SamlConfigPage';
const OAuth2ConfigPage = lazy(() => import('@/pages/admin/OAuth2ConfigPage'));
const SlackConfigPage = lazy(() => import('@/pages/admin/SlackConfigPage'));
const LangfuseConfigPage = lazy(() => import('@/pages/admin/LangfuseConfigPage'));
const GroupsListPage = lazy(() =>
  import('@/pages/admin/groups/GroupsListPage').then((m) => ({ default: m.GroupsListPage })),
);
const GroupDetailPage = lazy(() =>
  import('@/pages/admin/groups/GroupDetailPage').then((m) => ({ default: m.GroupDetailPage })),
);
const RolesPage = lazy(() =>
  import('@/pages/admin/RolesPage').then((m) => ({ default: m.RolesPage })),
);
import { LanguagesConfigPage } from '@/pages/admin/LanguagesConfigPage';
const CustomDriversPage = lazy(() => import('@/pages/admin/drivers/CustomDriversPage'));
const ConnectorsPage = lazy(() => import('@/pages/admin/connectors/ConnectorsPage'));
const OrganizationsListPage = lazy(() =>
  import('@/pages/admin/OrganizationsListPage').then((m) => ({ default: m.OrganizationsListPage })),
);
const OrganizationDetailPage = lazy(() =>
  import('@/pages/admin/OrganizationDetailPage').then((m) => ({ default: m.OrganizationDetailPage })),
);
const AuditorDashboardPage = lazy(() => import('@/pages/admin/AuditorDashboardPage'));
const CampaignListPage = lazy(() => import('@/pages/admin/attestation/CampaignListPage'));
const CampaignDetailPage = lazy(() => import('@/pages/admin/attestation/CampaignDetailPage'));
const LifecyclePoliciesListPage = lazy(
  () => import('@/pages/admin/lifecycle/LifecyclePoliciesListPage'),
);
const ErasureReviewQueuePage = lazy(
  () => import('@/pages/admin/lifecycle/ErasureReviewQueuePage'),
);
const ErasureSubmitPage = lazy(() => import('@/pages/lifecycle/ErasureSubmitPage'));
const AttestationWorklistPage = lazy(() => import('@/pages/reviews/AttestationWorklistPage'));
const ApiConnectorsListPage = lazy(() => import('@/pages/apigov/ApiConnectorsListPage'));
const ApiConnectorSettingsPage = lazy(() => import('@/pages/apigov/ApiConnectorSettingsPage'));
const ApiEditorPage = lazy(() => import('@/pages/apigov/ApiEditorPage'));
const ApiRequestsListPage = lazy(() => import('@/pages/apigov/ApiRequestsListPage'));
const ApiRequestDetailPage = lazy(() => import('@/pages/apigov/ApiRequestDetailPage'));
const ApiReviewQueuePage = lazy(() => import('@/pages/apigov/ApiReviewQueuePage'));
const RequestGroupListPage = lazy(() => import('@/pages/requestGroups/RequestGroupListPage'));
const GroupBuilderPage = lazy(() => import('@/pages/requestGroups/GroupBuilderPage'));
const RequestGroupDetailPage = lazy(() => import('@/pages/requestGroups/RequestGroupDetailPage'));
const RequestGroupReviewQueuePage = lazy(
  () => import('@/pages/requestGroups/RequestGroupReviewQueuePage'),
);
import { ProfilePage } from '@/pages/profile/ProfilePage';

export function App() {
  const setupRequired = useSetupStore((s) => s.setupRequired);
  const user = useAuthStore((s) => s.user);
  const location = useLocation();

  const guard = resolveRouteGuard({
    setupRequired,
    user,
    pathname: location.pathname,
  });
  if (guard.type === 'navigate') {
    return <Navigate to={guard.to} replace />;
  }

  const home = homePathForUser(user);

  return (
    <AntdApp>
      <MessageBridgeBinder />
      <NavigationBridgeBinder />
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
          path="/forgot-password"
          element={
            <Suspense fallback={null}>
              <ForgotPasswordPage />
            </Suspense>
          }
        />
        <Route
          path="/reset-password/:token"
          element={
            <Suspense fallback={null}>
              <ResetPasswordPage />
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
          path="/auth/saml/callback"
          element={
            <Suspense fallback={null}>
              <SamlCallbackPage />
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
          <Route path="/" element={<Navigate to={home} replace />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route
            path="/dashboard"
            element={
              <Suspense fallback={null}>
                <DashboardPage />
              </Suspense>
            }
          />
          <Route path="/editor" element={<QueryEditorPage />} />
          <Route
            path="/api-editor"
            element={
              <Suspense fallback={null}>
                <ApiEditorPage />
              </Suspense>
            }
          />
          <Route
            path="/api-requests"
            element={
              <Suspense fallback={null}>
                <ApiRequestsListPage />
              </Suspense>
            }
          />
          <Route
            path="/api-requests/:id"
            element={
              <Suspense fallback={null}>
                <ApiRequestDetailPage />
              </Suspense>
            }
          />
          <Route
            path="/api-reviews"
            element={
              <AuthGuard requirePermission={'API_REQUEST_REVIEW'}>
                <Suspense fallback={null}>
                  <ApiReviewQueuePage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/api-connectors"
            element={
              <AuthGuard requirePermission={'API_CONNECTOR_MANAGE'}>
                <Suspense fallback={null}>
                  <ApiConnectorsListPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/api-connectors/:id/settings"
            element={
              <AuthGuard requirePermission={'API_CONNECTOR_MANAGE'}>
                <Suspense fallback={null}>
                  <ApiConnectorSettingsPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/request-groups"
            element={
              <Suspense fallback={null}>
                <RequestGroupListPage />
              </Suspense>
            }
          />
          <Route
            path="/request-groups/new"
            element={
              <Suspense fallback={null}>
                <GroupBuilderPage />
              </Suspense>
            }
          />
          <Route
            path="/request-groups/:id/edit"
            element={
              <Suspense fallback={null}>
                <GroupBuilderPage />
              </Suspense>
            }
          />
          <Route
            path="/request-groups/reviews"
            element={
              <AuthGuard requirePermission={'QUERY_REVIEW'}>
                <Suspense fallback={null}>
                  <RequestGroupReviewQueuePage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/request-groups/:id"
            element={
              <Suspense fallback={null}>
                <RequestGroupDetailPage />
              </Suspense>
            }
          />
          <Route path="/access-requests" element={<RequestAccessPage />} />
          <Route path="/queries" element={<QueryListPage />} />
          <Route path="/queries/:id" element={<QueryDetailPage />} />
          <Route
            path="/reviews"
            element={
              <AuthGuard requirePermission={'QUERY_REVIEW'}>
                <ReviewQueuePage />
              </AuthGuard>
            }
          />
          <Route
            path="/reviews/:id/decide"
            element={
              <AuthGuard requirePermission={'QUERY_REVIEW'}>
                <Suspense fallback={null}>
                  <PushDecidePage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/reviews/attestations"
            element={
              <AuthGuard requirePermission={'ATTESTATION_REVIEW'}>
                <Suspense fallback={null}>
                  <AttestationWorklistPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/access-requests"
            element={
              <AuthGuard requirePermission={'ACCESS_REQUEST_REVIEW'}>
                <AccessRequestsQueuePage />
              </AuthGuard>
            }
          />
          <Route
            path="/datasources"
            element={
              <AuthGuard requirePermission={'DATASOURCE_MANAGE'}>
                <DatasourceListPage />
              </AuthGuard>
            }
          />
          <Route
            path="/datasources/new"
            element={
              <AuthGuard requirePermission={'DATASOURCE_MANAGE'}>
                <Suspense fallback={null}>
                  <DatasourceCreateWizardPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/datasources/:id/settings"
            element={
              <AuthGuard requirePermission={'DATASOURCE_MANAGE'}>
                <DatasourceSettingsPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/users"
            element={
              <AuthGuard requirePermission={'USER_MANAGE'}>
                <UsersPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/data-classifications"
            element={
              <AuthGuard requirePermission={'DATA_CLASSIFICATION_MANAGE'}>
                <Suspense fallback={null}>
                  <DataClassificationsPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/groups"
            element={
              <AuthGuard requirePermission={'GROUP_MANAGE'}>
                <Suspense fallback={null}>
                  <GroupsListPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/groups/:id"
            element={
              <AuthGuard requirePermission={'GROUP_MANAGE'}>
                <Suspense fallback={null}>
                  <GroupDetailPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/roles"
            element={
              <AuthGuard requirePermission={'ROLE_MANAGE'}>
                <Suspense fallback={null}>
                  <RolesPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/review-plans"
            element={
              <AuthGuard requirePermission={'REVIEW_PLAN_MANAGE'}>
                <ReviewPlansPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/routing-policies"
            element={
              <AuthGuard requirePermission={'ROUTING_POLICY_MANAGE'}>
                <RoutingPoliciesPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/attestation"
            element={
              <AuthGuard requirePermission={'ATTESTATION_CAMPAIGN_MANAGE'}>
                <Suspense fallback={null}>
                  <CampaignListPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/attestation/:id"
            element={
              <AuthGuard requirePermission={'ATTESTATION_CAMPAIGN_MANAGE'}>
                <Suspense fallback={null}>
                  <CampaignDetailPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/lifecycle/policies"
            element={
              <AuthGuard requirePermission={'RETENTION_POLICY_MANAGE'}>
                <Suspense fallback={null}>
                  <LifecyclePoliciesListPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/lifecycle/erasure-reviews"
            element={
              <AuthGuard requirePermission={'ERASURE_REVIEW'}>
                <Suspense fallback={null}>
                  <ErasureReviewQueuePage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/lifecycle/erasure"
            element={
              <Suspense fallback={null}>
                <ErasureSubmitPage />
              </Suspense>
            }
          />
          <Route
            path="/admin/audit-log"
            element={
              <AuthGuard requirePermission={'AUDIT_LOG_VIEW'}>
                <AuditLogPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/auditor"
            element={
              <AuthGuard requirePermission={'COMPLIANCE_REPORT_VIEW'}>
                <Suspense fallback={null}>
                  <AuditorDashboardPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/ai-configs"
            element={
              <AuthGuard requirePermission={'AI_MANAGE'}>
                <AiConfigListPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/ai-configs/new"
            element={
              <AuthGuard requirePermission={'AI_MANAGE'}>
                <Suspense fallback={null}>
                  <AiConfigCreateWizardPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/ai-configs/:id"
            element={
              <AuthGuard requirePermission={'AI_MANAGE'}>
                <Suspense fallback={null}>
                  <AiConfigEditPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/ai-analyses"
            element={
              <AuthGuard requirePermission={'AI_MANAGE'}>
                <Suspense fallback={null}>
                  <AiAnalysesPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/datasource-health"
            element={
              <AuthGuard requirePermission={'DATASOURCE_MANAGE'}>
                <Suspense fallback={null}>
                  <DatasourceHealthPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/anomalies"
            element={
              <AuthGuard requirePermission={'ANOMALY_MANAGE'}>
                <Suspense fallback={null}>
                  <AnomaliesPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/break-glass"
            element={
              <AuthGuard requirePermission={'BREAK_GLASS_VIEW'}>
                <Suspense fallback={null}>
                  <BreakGlassLogPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/notifications"
            element={
              <AuthGuard requirePermission={'NOTIFICATION_CHANNEL_MANAGE'}>
                <NotificationsPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/languages"
            element={
              <AuthGuard requirePermission={'LOCALIZATION_CONFIGURE'}>
                <LanguagesConfigPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/drivers"
            element={
              <AuthGuard requirePermission={'DATASOURCE_MANAGE'}>
                <Suspense fallback={null}>
                  <CustomDriversPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/connectors"
            element={
              <AuthGuard requirePermission={'DATASOURCE_MANAGE'}>
                <Suspense fallback={null}>
                  <ConnectorsPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/saml"
            element={
              <AuthGuard requirePermission={'SSO_CONFIGURE'}>
                <SamlConfigPage />
              </AuthGuard>
            }
          />
          <Route
            path="/admin/oauth2"
            element={
              <AuthGuard requirePermission={'SSO_CONFIGURE'}>
                <Suspense fallback={null}>
                  <OAuth2ConfigPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/slack"
            element={
              <AuthGuard requirePermission={'NOTIFICATION_CHANNEL_MANAGE'}>
                <Suspense fallback={null}>
                  <SlackConfigPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/langfuse"
            element={
              <AuthGuard requirePermission={'AI_MANAGE'}>
                <Suspense fallback={null}>
                  <LangfuseConfigPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/organizations"
            element={
              <AuthGuard requirePlatformAdmin>
                <Suspense fallback={null}>
                  <OrganizationsListPage />
                </Suspense>
              </AuthGuard>
            }
          />
          <Route
            path="/admin/organizations/:id"
            element={
              <AuthGuard requirePlatformAdmin>
                <Suspense fallback={null}>
                  <OrganizationDetailPage />
                </Suspense>
              </AuthGuard>
            }
          />
        </Route>
        <Route path="*" element={<Navigate to={home} replace />} />
      </Routes>
    </AntdApp>
  );
}
