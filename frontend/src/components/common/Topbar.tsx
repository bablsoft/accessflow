import { LogoutOutlined, MenuOutlined, MoonOutlined, SunOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { useNavigate } from 'react-router-dom';
import { NotificationBell } from './NotificationBell';
import './topbar.css';

interface TopbarProps {
  onOpenMobileNav: () => void;
}

export function Topbar({ onOpenMobileNav }: TopbarProps) {
  const { t } = useTranslation();
  const logout = useAuthStore((s) => s.logout);
  const theme = usePreferencesStore((s) => s.theme);
  const setTheme = usePreferencesStore((s) => s.setTheme);
  const navigate = useNavigate();

  const onLogout = async () => {
    await logout();
    navigate('/login');
  };

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
      <NotificationBell />
      <button className="af-icon-btn" onClick={onLogout} aria-label={t('common.sign_out')}>
        <LogoutOutlined />
      </button>
    </header>
  );
}
