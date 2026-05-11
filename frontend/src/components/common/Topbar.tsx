import {
  DownOutlined,
  LogoutOutlined,
  MenuOutlined,
  MoonOutlined,
  SunOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Dropdown, type MenuProps } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { useNavigate } from 'react-router-dom';
import { LanguageSwitcher } from './LanguageSwitcher';
import { NotificationBell } from './NotificationBell';
import './topbar.css';

interface TopbarProps {
  onOpenMobileNav: () => void;
}

export function Topbar({ onOpenMobileNav }: TopbarProps) {
  const { t } = useTranslation();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const theme = usePreferencesStore((s) => s.theme);
  const setTheme = usePreferencesStore((s) => s.setTheme);
  const navigate = useNavigate();

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
      <div style={{ flex: 1 }} />
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
          <DownOutlined style={{ fontSize: 10 }} />
        </button>
      </Dropdown>
    </header>
  );
}
