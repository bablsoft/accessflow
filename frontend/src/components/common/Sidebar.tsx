import { NavLink, useLocation } from 'react-router-dom';
import {
  EditOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
  InboxOutlined,
  DatabaseOutlined,
  TeamOutlined,
  SafetyCertificateOutlined,
  SafetyOutlined,
  TagsOutlined,
  ExperimentOutlined,
  BellOutlined,
  ApartmentOutlined,
  NodeIndexOutlined,
  IdcardOutlined,
  GlobalOutlined,
  ApiOutlined,
  LoginOutlined,
  SlackOutlined,
  LeftOutlined,
  RightOutlined,
  CloseOutlined,
  BarChartOutlined,
  DashboardOutlined,
  HomeOutlined,
  KeyOutlined,
  UnlockOutlined,
  LineChartOutlined,
  AppstoreOutlined,
  BankOutlined,
  AuditOutlined,
  WarningOutlined,
  FileProtectOutlined,
  FieldTimeOutlined,
  CheckSquareOutlined,
  BlockOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AuthUser } from '@/api/auth';
import { hasAnyPermission, type Permission } from '@/utils/permissions';
import { APP_VERSION } from '@/config/version';
import { userDisplay } from '@/utils/userDisplay';
import { roleLabel } from '@/utils/enumLabels';
import { Avatar } from './Avatar';
import { LogoMark } from './LogoMark';
import './sidebar.css';

interface NavItem {
  id: string;
  to: string;
  label: string;
  icon: React.ReactNode;
  /** Visible when the user holds ANY of these permissions (AF-522). */
  permissions: Permission[];
  /** When true, visible to platform admins regardless of role (AF-456). */
  platformAdmin?: boolean;
  badge?: 'pending';
}

interface NavGroup {
  id: string;
  label: string;
  items: NavItem[];
}

interface SidebarProps {
  user: AuthUser;
  pendingCount: number;
  collapsed: boolean;
  onToggle: () => void;
  mobileOpen: boolean;
  onMobileClose: () => void;
}

export function Sidebar({
  user, pendingCount, collapsed, onToggle, mobileOpen, onMobileClose,
}: SidebarProps) {
  const { t } = useTranslation();
  const location = useLocation();

  const GROUPS: NavGroup[] = [
    {
      id: 'workflow',
      label: t('nav.group_workflow'),
      items: [
        { id: 'dashboard', to: '/dashboard', label: t('nav.dashboard'), icon: <HomeOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
        { id: 'editor', to: '/editor', label: t('nav.editor'), icon: <EditOutlined />, permissions: ['QUERY_SUBMIT_DML'] },
        { id: 'queries', to: '/queries', label: t('nav.queries'), icon: <UnorderedListOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
        { id: 'reviews', to: '/reviews', label: t('nav.reviews'), icon: <InboxOutlined />, permissions: ['QUERY_REVIEW'], badge: 'pending' },
        { id: 'api-editor', to: '/api-editor', label: t('nav.apiEditor'), icon: <ApiOutlined />, permissions: ['QUERY_SUBMIT_DML'] },
        { id: 'api-requests', to: '/api-requests', label: t('nav.apiRequests'), icon: <UnorderedListOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
        { id: 'api-reviews', to: '/api-reviews', label: t('nav.apiReviews'), icon: <InboxOutlined />, permissions: ['API_REQUEST_REVIEW'] },
        { id: 'request-groups', to: '/request-groups', label: t('nav.requestGroups'), icon: <BlockOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
        { id: 'request-group-reviews', to: '/request-groups/reviews', label: t('nav.requestGroupReviews'), icon: <InboxOutlined />, permissions: ['QUERY_REVIEW'] },
        { id: 'request-access', to: '/access-requests', label: t('nav.request_access'), icon: <KeyOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
        { id: 'request-erasure', to: '/lifecycle/erasure', label: t('nav.request_erasure'), icon: <FieldTimeOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
        { id: 'erasure-review', to: '/lifecycle/erasure-reviews', label: t('nav.erasure_review'), icon: <FileProtectOutlined />, permissions: ['ERASURE_REVIEW'] },
        { id: 'attestation-reviews', to: '/reviews/attestations', label: t('nav.attestation_reviews'), icon: <CheckSquareOutlined />, permissions: ['ATTESTATION_REVIEW'] },
      ],
    },
    {
      id: 'data',
      label: t('nav.group_data'),
      items: [
        { id: 'datasources', to: '/datasources', label: t('nav.datasources'), icon: <DatabaseOutlined />, permissions: ['DATASOURCE_MANAGE'] },
        { id: 'api-connectors', to: '/api-connectors', label: t('nav.apiConnectors'), icon: <ApiOutlined />, permissions: ['API_CONNECTOR_MANAGE'] },
        { id: 'connectors', to: '/admin/connectors', label: t('nav.connectors'), icon: <AppstoreOutlined />, permissions: ['DATASOURCE_MANAGE'] },
        { id: 'drivers', to: '/admin/drivers', label: t('nav.custom_drivers'), icon: <ApiOutlined />, permissions: ['DATASOURCE_MANAGE'] },
      ],
    },
    {
      id: 'security',
      label: t('nav.group_security'),
      items: [
        { id: 'users', to: '/admin/users', label: t('nav.users'), icon: <TeamOutlined />, permissions: ['USER_MANAGE'] },
        { id: 'groups', to: '/admin/groups', label: t('nav.groups'), icon: <TeamOutlined />, permissions: ['GROUP_MANAGE'] },
        { id: 'roles', to: '/admin/roles', label: t('nav.roles'), icon: <SafetyOutlined />, permissions: ['ROLE_MANAGE'] },
        { id: 'access-requests', to: '/admin/access-requests', label: t('nav.access_requests'), icon: <UnlockOutlined />, permissions: ['ACCESS_REQUEST_REVIEW'] },
        { id: 'review-plans', to: '/admin/review-plans', label: t('nav.review_plans'), icon: <ApartmentOutlined />, permissions: ['REVIEW_PLAN_MANAGE'] },
        { id: 'routing-policies', to: '/admin/routing-policies', label: t('nav.routing_policies'), icon: <NodeIndexOutlined />, permissions: ['ROUTING_POLICY_MANAGE'] },
        { id: 'data-classifications', to: '/admin/data-classifications', label: t('nav.data_classifications'), icon: <TagsOutlined />, permissions: ['DATA_CLASSIFICATION_MANAGE'] },
        { id: 'attestation', to: '/admin/attestation', label: t('nav.attestation'), icon: <FileProtectOutlined />, permissions: ['ATTESTATION_CAMPAIGN_MANAGE'] },
        { id: 'lifecycle', to: '/admin/lifecycle/policies', label: t('nav.lifecycle'), icon: <FieldTimeOutlined />, permissions: ['RETENTION_POLICY_MANAGE'] },
        { id: 'break-glass', to: '/admin/break-glass', label: t('nav.break_glass'), icon: <ThunderboltOutlined />, permissions: ['BREAK_GLASS_VIEW'] },
        { id: 'audit', to: '/admin/audit-log', label: t('nav.audit'), icon: <SafetyCertificateOutlined />, permissions: ['AUDIT_LOG_VIEW'] },
        { id: 'auditor', to: '/admin/auditor', label: t('nav.auditor'), icon: <AuditOutlined />, permissions: ['COMPLIANCE_REPORT_VIEW'] },
        { id: 'saml', to: '/admin/saml', label: t('nav.saml'), icon: <IdcardOutlined />, permissions: ['SSO_CONFIGURE'] },
        { id: 'oauth2', to: '/admin/oauth2', label: t('nav.oauth2'), icon: <LoginOutlined />, permissions: ['SSO_CONFIGURE'] },
        { id: 'slack', to: '/admin/slack', label: t('nav.slack'), icon: <SlackOutlined />, permissions: ['NOTIFICATION_CHANNEL_MANAGE'] },
      ],
    },
    {
      id: 'system',
      label: t('nav.group_system'),
      items: [
        { id: 'ai', to: '/admin/ai-configs', label: t('nav.ai_configs'), icon: <ExperimentOutlined />, permissions: ['AI_MANAGE'] },
        { id: 'ai-analyses', to: '/admin/ai-analyses', label: t('nav.ai_analyses'), icon: <BarChartOutlined />, permissions: ['AI_MANAGE'] },
        { id: 'datasource-health', to: '/admin/datasource-health', label: t('nav.datasource_health'), icon: <DashboardOutlined />, permissions: ['DATASOURCE_MANAGE'] },
        { id: 'anomalies', to: '/admin/anomalies', label: t('nav.anomalies'), icon: <WarningOutlined />, permissions: ['ANOMALY_MANAGE'] },
        { id: 'channels', to: '/admin/notifications', label: t('nav.notifications'), icon: <BellOutlined />, permissions: ['NOTIFICATION_CHANNEL_MANAGE'] },
        { id: 'langfuse', to: '/admin/langfuse', label: t('nav.langfuse'), icon: <LineChartOutlined />, permissions: ['AI_MANAGE'] },
        { id: 'languages', to: '/admin/languages', label: t('nav.languages'), icon: <GlobalOutlined />, permissions: ['LOCALIZATION_CONFIGURE'] },
      ],
    },
    {
      id: 'platform',
      label: t('nav.group_platform'),
      items: [
        { id: 'organizations', to: '/admin/organizations', label: t('nav.organizations'), icon: <BankOutlined />, permissions: [], platformAdmin: true },
      ],
    },
  ];

  const visibleGroups = GROUPS
    .map((g) => ({
      ...g,
      items: g.items.filter(
        (it) => hasAnyPermission(user, it.permissions)
          || (it.platformAdmin && user.platform_admin),
      ),
    }))
    .filter((g) => g.items.length > 0);

  return (
    <>
      <aside className={`af-sidebar${collapsed ? ' collapsed' : ''}${mobileOpen ? ' mobile-open' : ''}`}>
        <div className="af-sidebar-brand">
          <LogoMark size={26} className="af-logo-mark" />
          {!collapsed && (
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 13.5, fontWeight: 600, letterSpacing: '-0.01em' }}>
                {t('common.app_name')}
              </div>
              <div
                className="mono muted"
                style={{ fontSize: 9.5, textTransform: 'lowercase' }}
                aria-label={t('nav.version', { version: APP_VERSION })}
              >
                v{APP_VERSION}
              </div>
            </div>
          )}
          <button className="af-sidebar-collapse-btn" onClick={onToggle} aria-label={t('common.toggle_sidebar')}>
            {collapsed ? <RightOutlined /> : <LeftOutlined />}
          </button>
          <button className="af-icon-btn af-mobile-menu-btn" onClick={onMobileClose} aria-label={t('common.close_menu')}>
            <CloseOutlined />
          </button>
        </div>
        <nav className="af-sidebar-nav">
          {visibleGroups.map((group, gi) => (
            <div key={group.id} className="af-sidebar-group">
              {collapsed ? (
                gi > 0 ? <div className="af-sidebar-divider-line" /> : null
              ) : (
                <div className="af-sidebar-divider mono" aria-label={group.label}>
                  {group.label}
                </div>
              )}
              {group.items.map((item) => {
                const isActive =
                  location.pathname === item.to ||
                  (item.to !== '/' && location.pathname.startsWith(item.to + '/'));
                return (
                  <NavLink
                    key={item.id}
                    to={item.to}
                    className={`af-sidebar-item${isActive ? ' active' : ''}`}
                    title={collapsed ? item.label : undefined}
                  >
                    <span className="af-sidebar-icon">{item.icon}</span>
                    {!collapsed && <span style={{ flex: 1, textAlign: 'left' }}>{item.label}</span>}
                    {item.badge === 'pending' && pendingCount > 0 && (
                      collapsed ? (
                        <span className="af-badge-dot" />
                      ) : (
                        <span className="af-sidebar-badge mono">{pendingCount}</span>
                      )
                    )}
                  </NavLink>
                );
              })}
            </div>
          ))}
        </nav>
        <div className="af-sidebar-footer">
          {(() => {
            const label = userDisplay(user.display_name, user.email);
            const role = roleLabel(t, user.role);
            return (
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  justifyContent: collapsed ? 'center' : 'flex-start',
                }}
                title={collapsed ? `${label} · ${role}` : undefined}
              >
                <Avatar name={label} size={28} />
                {!collapsed && (
                  <div style={{ minWidth: 0, flex: 1 }}>
                    <div
                      style={{
                        fontSize: 12.5,
                        fontWeight: 500,
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                      }}
                    >
                      {label}
                    </div>
                    <div className="mono muted" style={{ fontSize: 10 }}>{role}</div>
                  </div>
                )}
              </div>
            );
          })()}
        </div>
      </aside>
      <div
        className={`af-sidebar-scrim${mobileOpen ? ' visible' : ''}`}
        aria-hidden={!mobileOpen}
        onClick={onMobileClose}
      />
    </>
  );
}
