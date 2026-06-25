import { NavLink, useLocation } from 'react-router-dom';
import {
  EditOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
  InboxOutlined,
  DatabaseOutlined,
  TeamOutlined,
  SafetyCertificateOutlined,
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
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { Role } from '@/types/api';
import type { AuthUser } from '@/api/auth';
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
  roles: Role[];
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
        { id: 'dashboard', to: '/dashboard', label: t('nav.dashboard'), icon: <HomeOutlined />, roles: ['READONLY', 'ANALYST', 'REVIEWER', 'ADMIN'] },
        { id: 'editor', to: '/editor', label: t('nav.editor'), icon: <EditOutlined />, roles: ['ANALYST', 'REVIEWER', 'ADMIN'] },
        { id: 'queries', to: '/queries', label: t('nav.queries'), icon: <UnorderedListOutlined />, roles: ['READONLY', 'ANALYST', 'REVIEWER', 'ADMIN'] },
        { id: 'reviews', to: '/reviews', label: t('nav.reviews'), icon: <InboxOutlined />, roles: ['REVIEWER', 'ADMIN'], badge: 'pending' },
        { id: 'request-access', to: '/access-requests', label: t('nav.request_access'), icon: <KeyOutlined />, roles: ['READONLY', 'ANALYST', 'REVIEWER', 'ADMIN'] },
      ],
    },
    {
      id: 'data',
      label: t('nav.group_data'),
      items: [
        { id: 'datasources', to: '/datasources', label: t('nav.datasources'), icon: <DatabaseOutlined />, roles: ['ADMIN'] },
        { id: 'connectors', to: '/admin/connectors', label: t('nav.connectors'), icon: <AppstoreOutlined />, roles: ['ADMIN'] },
        { id: 'drivers', to: '/admin/drivers', label: t('nav.custom_drivers'), icon: <ApiOutlined />, roles: ['ADMIN'] },
      ],
    },
    {
      id: 'security',
      label: t('nav.group_security'),
      items: [
        { id: 'users', to: '/admin/users', label: t('nav.users'), icon: <TeamOutlined />, roles: ['ADMIN'] },
        { id: 'groups', to: '/admin/groups', label: t('nav.groups'), icon: <TeamOutlined />, roles: ['ADMIN'] },
        { id: 'access-requests', to: '/admin/access-requests', label: t('nav.access_requests'), icon: <UnlockOutlined />, roles: ['REVIEWER', 'ADMIN'] },
        { id: 'review-plans', to: '/admin/review-plans', label: t('nav.review_plans'), icon: <ApartmentOutlined />, roles: ['ADMIN'] },
        { id: 'routing-policies', to: '/admin/routing-policies', label: t('nav.routing_policies'), icon: <NodeIndexOutlined />, roles: ['ADMIN'] },
        { id: 'data-classifications', to: '/admin/data-classifications', label: t('nav.data_classifications'), icon: <TagsOutlined />, roles: ['ADMIN'] },
        { id: 'break-glass', to: '/admin/break-glass', label: t('nav.break_glass'), icon: <ThunderboltOutlined />, roles: ['AUDITOR', 'ADMIN'] },
        { id: 'audit', to: '/admin/audit-log', label: t('nav.audit'), icon: <SafetyCertificateOutlined />, roles: ['ADMIN'] },
        { id: 'auditor', to: '/admin/auditor', label: t('nav.auditor'), icon: <AuditOutlined />, roles: ['AUDITOR', 'ADMIN'] },
        { id: 'saml', to: '/admin/saml', label: t('nav.saml'), icon: <IdcardOutlined />, roles: ['ADMIN'] },
        { id: 'oauth2', to: '/admin/oauth2', label: t('nav.oauth2'), icon: <LoginOutlined />, roles: ['ADMIN'] },
        { id: 'slack', to: '/admin/slack', label: t('nav.slack'), icon: <SlackOutlined />, roles: ['ADMIN'] },
      ],
    },
    {
      id: 'system',
      label: t('nav.group_system'),
      items: [
        { id: 'ai', to: '/admin/ai-configs', label: t('nav.ai_configs'), icon: <ExperimentOutlined />, roles: ['ADMIN'] },
        { id: 'ai-analyses', to: '/admin/ai-analyses', label: t('nav.ai_analyses'), icon: <BarChartOutlined />, roles: ['ADMIN'] },
        { id: 'langfuse', to: '/admin/langfuse', label: t('nav.langfuse'), icon: <LineChartOutlined />, roles: ['ADMIN'] },
        { id: 'datasource-health', to: '/admin/datasource-health', label: t('nav.datasource_health'), icon: <DashboardOutlined />, roles: ['ADMIN'] },
        { id: 'anomalies', to: '/admin/anomalies', label: t('nav.anomalies'), icon: <WarningOutlined />, roles: ['ADMIN'] },
        { id: 'channels', to: '/admin/notifications', label: t('nav.notifications'), icon: <BellOutlined />, roles: ['ADMIN'] },
        { id: 'languages', to: '/admin/languages', label: t('nav.languages'), icon: <GlobalOutlined />, roles: ['ADMIN'] },
      ],
    },
    {
      id: 'platform',
      label: t('nav.group_platform'),
      items: [
        { id: 'organizations', to: '/admin/organizations', label: t('nav.organizations'), icon: <BankOutlined />, roles: [], platformAdmin: true },
      ],
    },
  ];

  const visibleGroups = GROUPS
    .map((g) => ({
      ...g,
      items: g.items.filter(
        (it) => it.roles.includes(user.role) || (it.platformAdmin && user.platform_admin),
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
