import { useEffect, useMemo, useRef, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { buildNavGroups, Sidebar } from '@/components/common/Sidebar';
import { activeNavEntry, flattenNav, visibleNavGroups } from '@/components/common/navModel';
import { CommandPalette } from '@/components/command/CommandPalette';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useEnterAnimation } from '@/motion/useEnterAnimation';
import { SetupProgressWidget } from '@/components/common/SetupProgressWidget';
import { Topbar } from '@/components/common/Topbar';
import { HelpChatLauncher } from '@/components/help/HelpChatLauncher';
import { RealtimeBridge } from '@/realtime/RealtimeBridge';
import { ErrorBoundary } from '@/components/layout/ErrorBoundary';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { usePendingReviewCounts } from '@/hooks/usePendingReviewCounts';
import '@/components/common/common.css';
import './app-layout.css';

export function AppLayout() {
  const user = useAuthStore((s) => s.user);
  const sidebarCollapsed = usePreferencesStore((s) => s.sidebarCollapsed);
  const toggleSidebar = usePreferencesStore((s) => s.toggleSidebar);
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();
  const { t } = useTranslation();

  // One badge for the unified review queue (#772): the sum over every queue the user may work.
  const { total: pendingCount } = usePendingReviewCounts();

  const [paletteOpen, setPaletteOpen] = useState(false);
  const recordNavVisit = usePreferencesStore((s) => s.recordNavVisit);
  // Between the phone drawer (≤720px) and a full desktop, the sidebar is an icon rail.
  const narrow = useMediaQuery('(min-width: 721px) and (max-width: 1024px)');
  const pageRef = useRef<HTMLElement>(null);
  useEnterAnimation(pageRef, location.pathname);

  useEffect(() => {
    setMobileOpen(false);
  }, [location.pathname]);

  const activeNavId = useMemo(() => {
    if (!user) return null;
    const entries = flattenNav(visibleNavGroups(buildNavGroups(t), user));
    return activeNavEntry(entries, location.pathname)?.item.id ?? null;
  }, [t, user, location.pathname]);

  useEffect(() => {
    if (activeNavId) recordNavVisit(activeNavId);
  }, [activeNavId, recordNavVisit]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && !event.altKey && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  if (!user) return null;

  return (
    <div className="af-app-shell">
      <a className="af-skip-link" href="#main-content">
        {t('common.skip_to_content')}
      </a>
      <RealtimeBridge />
      <Sidebar
        user={user}
        pendingCount={pendingCount}
        collapsed={sidebarCollapsed || narrow}
        onToggle={toggleSidebar}
        mobileOpen={mobileOpen}
        onMobileClose={() => setMobileOpen(false)}
      />
      <div className="af-app-main">
        <Topbar
          onOpenMobileNav={() => setMobileOpen(true)}
          onOpenCommandPalette={() => setPaletteOpen(true)}
        />
        <div className="af-app-content">
          <SetupProgressWidget />
          <main ref={pageRef} id="main-content" tabIndex={-1} className="af-app-content-page">
            <ErrorBoundary resetKey={location.pathname}>
              <Outlet />
            </ErrorBoundary>
          </main>
        </div>
      </div>
      {/* Authenticated-only by construction: AppLayout never renders on /login or /setup. */}
      <HelpChatLauncher />
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} user={user} />
    </div>
  );
}
