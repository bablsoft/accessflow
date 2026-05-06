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
  LeftOutlined,
  RightOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import type { Role } from '@/types/api';
import type { AuthUser } from '@/api/auth';
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

const NAV: NavEntry[] = [
  { id: 'editor', to: '/editor', label: 'Query editor', icon: <EditOutlined />, roles: ['ANALYST', 'REVIEWER', 'ADMIN'] },
  { id: 'queries', to: '/queries', label: 'Query history', icon: <UnorderedListOutlined />, roles: ['READONLY', 'ANALYST', 'REVIEWER', 'ADMIN'] },
  { id: 'reviews', to: '/reviews', label: 'Review queue', icon: <InboxOutlined />, roles: ['REVIEWER', 'ADMIN'], badge: 'pending' },
  { type: 'divider', label: 'Admin', roles: ['ADMIN'] },
  { id: 'datasources', to: '/datasources', label: 'Datasources', icon: <DatabaseOutlined />, roles: ['ADMIN'] },
  { id: 'users', to: '/admin/users', label: 'Users', icon: <TeamOutlined />, roles: ['ADMIN'] },
  { id: 'audit', to: '/admin/audit-log', label: 'Audit log', icon: <SafetyCertificateOutlined />, roles: ['ADMIN'] },
  { id: 'ai', to: '/admin/ai-config', label: 'AI config', icon: <ExperimentOutlined />, roles: ['ADMIN'] },
  { id: 'channels', to: '/admin/notifications', label: 'Notifications', icon: <BellOutlined />, roles: ['ADMIN'] },
];

interface SidebarProps {
  user: AuthUser;
  edition: 'COMMUNITY' | 'ENTERPRISE';
  pendingCount: number;
  collapsed: boolean;
  onToggle: () => void;
  mobileOpen: boolean;
  onMobileClose: () => void;
}

export function Sidebar({
  user, edition, pendingCount, collapsed, onToggle, mobileOpen, onMobileClose,
}: SidebarProps) {
  const location = useLocation();
  const items = NAV.filter((n) => n.roles.includes(user.role));
  return (
    <>
      <aside className={`af-sidebar${collapsed ? ' collapsed' : ''}${mobileOpen ? ' mobile-open' : ''}`}>
        <div className="af-sidebar-brand">
          <div className="af-logo-mark">AF</div>
          {!collapsed && (
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 13.5, fontWeight: 600, letterSpacing: '-0.01em' }}>
                AccessFlow
              </div>
              <div className="mono muted" style={{ fontSize: 9.5, textTransform: 'lowercase' }}>
                {edition.toLowerCase()} · v0.1.0
              </div>
            </div>
          )}
          <button className="af-sidebar-collapse-btn" onClick={onToggle} aria-label="Toggle sidebar">
            {collapsed ? <RightOutlined /> : <LeftOutlined />}
          </button>
          <button className="af-icon-btn af-mobile-menu-btn" onClick={onMobileClose} aria-label="Close menu">
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
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              justifyContent: collapsed ? 'center' : 'flex-start',
            }}
            title={collapsed ? `${user.display_name} · ${user.role}` : undefined}
          >
            <Avatar name={user.display_name} size={28} />
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
                  {user.display_name}
                </div>
                <div className="mono muted" style={{ fontSize: 10 }}>{user.role}</div>
              </div>
            )}
          </div>
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
