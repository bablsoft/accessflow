import {
  DownOutlined,
  LogoutOutlined,
  MenuOutlined,
  MoonOutlined,
  RightOutlined,
  SearchOutlined,
  SunOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Dropdown, Tooltip, type MenuProps } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useConnectionStatus } from '@/hooks/useConnectionStatus';
import { buildNavGroups } from './Sidebar';
import { activeNavEntry, flattenNav, visibleNavGroups } from './navModel';
import { LanguageSwitcher } from './LanguageSwitcher';
import { NotificationBell } from './NotificationBell';
import './topbar.css';

interface TopbarProps {
  onOpenMobileNav: () => void;
  onOpenCommandPalette: () => void;
}

const IS_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);

export function Topbar({ onOpenMobileNav, onOpenCommandPalette }: TopbarProps) {
  const { t } = useTranslation();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const theme = usePreferencesStore((s) => s.theme);
  const setTheme = usePreferencesStore((s) => s.setTheme);
  const navigate = useNavigate();
  const location = useLocation();
  const connection = useConnectionStatus();
  const here = user
    ? activeNavEntry(flattenNav(visibleNavGroups(buildNavGroups(t), user)), location.pathname)
    : null;
  const crumbs = here ? [here.groupLabel, here.subLabel].filter(Boolean) : [];

  const onLogout = async () => {
    await logout();
    navigate('/login');
  };

  const menuItems: MenuProps['items'] = [
    {
      key: 'profile',
      icon: <UserOutlined />,
      label: t('user_menu.profile'),
      onClick: () => navigate('/profile'),
    },
    { type: 'divider' },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: t('user_menu.sign_out'),
      onClick: onLogout,
    },
  ];

  return (
    <header className="af-topbar">
      <button
        className="af-icon-btn af-mobile-menu-btn"
        onClick={onOpenMobileNav}
        aria-label={t('common.open_menu')}
      >
        <MenuOutlined />
      </button>
      {here ? (
        <nav className="af-topbar-crumbs" aria-label={t('common.breadcrumb')}>
          {crumbs.map((label) => (
            <span key={label} className="af-topbar-crumb">
              {label}
              <RightOutlined aria-hidden />
            </span>
          ))}
          {location.pathname === here.item.to ? (
            <span className="af-topbar-crumb-current" aria-current="page">{here.item.label}</span>
          ) : (
            // On a page below the destination (a detail or settings page) the crumb leads back.
            <Link to={here.item.to}>{here.item.label}</Link>
          )}
        </nav>
      ) : (
        <div className="af-topbar-crumbs" />
      )}
      <button type="button" className="af-topbar-search" onClick={onOpenCommandPalette}>
        <SearchOutlined aria-hidden />
        <span className="af-topbar-search-label">{t('command.open')}</span>
        <span className="kbd" aria-hidden>{IS_MAC ? '⌘K' : 'Ctrl K'}</span>
      </button>
      {connection !== 'idle' && (
        <Tooltip title={t(`realtime_status.${connection}`)}>
          <span
            className={`af-topbar-conn af-topbar-conn--${connection}`}
            role="status"
            aria-label={t(`realtime_status.${connection}`)}
          />
        </Tooltip>
      )}
      <div className="af-topbar-pills">
        <div className="af-theme-toggle">
          <button
            className={theme === 'light' ? 'on' : ''}
            onClick={() => setTheme('light')}
            aria-label={t('common.light_theme')}
          >
            <SunOutlined />
          </button>
          <button
            className={theme === 'dark' ? 'on' : ''}
            onClick={() => setTheme('dark')}
            aria-label={t('common.dark_theme')}
          >
            <MoonOutlined />
          </button>
        </div>
      </div>
      <LanguageSwitcher />
      <NotificationBell />
      <Dropdown menu={{ items: menuItems }} trigger={['click']} placement="bottomRight">
        <button
          className="af-user-menu-trigger"
          aria-label={t('user_menu.open')}
          type="button"
        >
          <UserOutlined />
          <span className="af-user-menu-name">{user?.display_name ?? user?.email ?? ''}</span>
          <DownOutlined className="u-text-2xs" />
        </button>
      </Dropdown>
    </header>
  );
}
