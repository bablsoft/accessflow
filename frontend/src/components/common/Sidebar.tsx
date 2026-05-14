import { NavLink, useLocation } from 'react-router-dom';
import {
  EditOutlined,
  UnorderedListOutlined,
  InboxOutlined,
  DatabaseOutlined,
  TeamOutlined,
  SafetyCertificateOutlined,
  ExperimentOutlined,
  BellOutlined,
  ApartmentOutlined,
  IdcardOutlined,
  GlobalOutlined,
  ApiOutlined,
  LoginOutlined,
  LeftOutlined,
  RightOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { Role } from '@/types/api';
import type { AuthUser } from '@/api/auth';
import { APP_VERSION } from '@/config/version';
import { userDisplay } from '@/utils/userDisplay';
import { Avatar } from './Avatar';
import './sidebar.css';

interface NavItem {
  id: string;
  to: string;
  label: string;
  icon: React.ReactNode;
  roles: Role[];
  badge?: 'pending';
}
interface NavDivider {
  type: 'divider';
  label: string;
  roles: Role[];
}
type NavEntry = NavItem | NavDivider;

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

  const NAV: NavEntry[] = [
    { id: 'editor', to: '/editor', label: t('nav.editor'), icon: <EditOutlined />, roles: ['ANALYST', 'REVIEWER', 'ADMIN'] },
    { id: 'queries', to: '/queries', label: t('nav.queries'), icon: <UnorderedListOutlined />, roles: ['READONLY', 'ANALYST', 'REVIEWER', 'ADMIN'] },
    { id: 'reviews', to: '/reviews', label: t('nav.reviews'), icon: <InboxOutlined />, roles: ['REVIEWER', 'ADMIN'], badge: 'pending' },
    { type: 'divider', label: t('nav.admin_divider'), roles: ['ADMIN'] },
    { id: 'datasources', to: '/datasources', label: t('nav.datasources'), icon: <DatabaseOutlined />, roles: ['ADMIN'] },
    { id: 'users', to: '/admin/users', label: t('nav.users'), icon: <TeamOutlined />, roles: ['ADMIN'] },
    { id: 'review-plans', to: '/admin/review-plans', label: t('nav.review_plans'), icon: <ApartmentOutlined />, roles: ['ADMIN'] },
    { id: 'audit', to: '/admin/audit-log', label: t('nav.audit'), icon: <SafetyCertificateOutlined />, roles: ['ADMIN'] },
    { id: 'ai', to: '/admin/ai-configs', label: t('nav.ai_configs'), icon: <ExperimentOutlined />, roles: ['ADMIN'] },
    { id: 'channels', to: '/admin/notifications', label: t('nav.notifications'), icon: <BellOutlined />, roles: ['ADMIN'] },
    { id: 'drivers', to: '/admin/drivers', label: t('nav.custom_drivers'), icon: <ApiOutlined />, roles: ['ADMIN'] },
    { id: 'languages', to: '/admin/languages', label: t('nav.languages'), icon: <GlobalOutlined />, roles: ['ADMIN'] },
    { id: 'saml', to: '/admin/saml', label: t('nav.saml'), icon: <IdcardOutlined />, roles: ['ADMIN'] },
    { id: 'oauth2', to: '/admin/oauth2', label: t('nav.oauth2'), icon: <LoginOutlined />, roles: ['ADMIN'] },
  ];

  const items = NAV.filter((n) => n.roles.includes(user.role));
  return (
    <>
      <aside className={`af-sidebar${collapsed ? ' collapsed' : ''}${mobileOpen ? ' mobile-open' : ''}`}>
        <div className="af-sidebar-brand">
          <div className="af-logo-mark">AF</div>
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
          {items.map((item, i) => {
            if ('type' in item) {
              if (collapsed) return <div key={`d-${i}`} className="af-sidebar-divider-line" />;
              return (
                <div key={`d-${i}`} className="af-sidebar-divider mono">
                  {item.label}
                </div>
              );
            }
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
        </nav>
        <div className="af-sidebar-footer">
          {(() => {
            const label = userDisplay(user.display_name, user.email);
            return (
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  justifyContent: collapsed ? 'center' : 'flex-start',
                }}
                title={collapsed ? `${label} · ${user.role}` : undefined}
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
                    <div className="mono muted" style={{ fontSize: 10 }}>{user.role}</div>
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
