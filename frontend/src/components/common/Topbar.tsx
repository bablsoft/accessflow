import { useState } from 'react';
import { Input, Select } from 'antd';
import {
  SearchOutlined,
  BellOutlined,
  LogoutOutlined,
  MenuOutlined,
  SunOutlined,
  MoonOutlined,
} from '@ant-design/icons';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { USERS } from '@/mocks/data';
import { useNavigate } from 'react-router-dom';
import './topbar.css';

interface TopbarProps {
  onOpenMobileNav: () => void;
}

export function Topbar({ onOpenMobileNav }: TopbarProps) {
  const [search, setSearch] = useState('');
  const userId = useAuthStore((s) => s.userId);
  const setUserId = useAuthStore((s) => s.setUserId);
  const edition = useAuthStore((s) => s.edition);
  const setEdition = useAuthStore((s) => s.setEdition);
  const logout = useAuthStore((s) => s.logout);
  const theme = usePreferencesStore((s) => s.theme);
  const setTheme = usePreferencesStore((s) => s.setTheme);
  const navigate = useNavigate();

  const onLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <header className="af-topbar">
      <button
        className="af-icon-btn af-mobile-menu-btn"
        onClick={onOpenMobileNav}
        aria-label="Open menu"
      >
        <MenuOutlined />
      </button>
      <div className="af-topbar-search">
        <Input
          prefix={<SearchOutlined style={{ color: 'var(--fg-faint)' }} />}
          placeholder="Search…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          suffix={<span className="kbd">⌘K</span>}
        />
      </div>
      <div style={{ flex: 1 }} />
      <div className="af-topbar-pills">
        <Select
          size="small"
          value={userId ?? undefined}
          style={{ minWidth: 200 }}
          className="hide-mobile"
          onChange={setUserId}
          options={USERS.filter((u) => u.active)
            .slice(0, 8)
            .map((u) => ({
              value: u.id,
              label: `${u.display_name} · ${u.role}`,
            }))}
        />
        <Select
          size="small"
          value={edition}
          className="hide-tablet"
          onChange={setEdition}
          style={{ width: 120 }}
          options={[
            { value: 'COMMUNITY', label: 'Community' },
            { value: 'ENTERPRISE', label: 'Enterprise' },
          ]}
        />
        <div className="af-theme-toggle">
          <button
            className={theme === 'light' ? 'on' : ''}
            onClick={() => setTheme('light')}
            aria-label="Light theme"
          >
            <SunOutlined />
          </button>
          <button
            className={theme === 'dark' ? 'on' : ''}
            onClick={() => setTheme('dark')}
            aria-label="Dark theme"
          >
            <MoonOutlined />
          </button>
        </div>
      </div>
      <button className="af-icon-btn hide-mobile" aria-label="Notifications">
        <BellOutlined />
      </button>
      <button className="af-icon-btn" onClick={onLogout} aria-label="Sign out">
        <LogoutOutlined />
      </button>
    </header>
  );
}
