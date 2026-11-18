import { useEffect, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Sidebar } from '@/components/common/Sidebar';
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

  useEffect(() => {
    setMobileOpen(false);
  }, [location.pathname]);

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
        collapsed={sidebarCollapsed}
        onToggle={toggleSidebar}
        mobileOpen={mobileOpen}
        onMobileClose={() => setMobileOpen(false)}
      />
      <div className="af-app-main">
        <Topbar onOpenMobileNav={() => setMobileOpen(true)} />
        <div className="af-app-content">
          <SetupProgressWidget />
          <main id="main-content" tabIndex={-1} className="af-app-content-page">
            <ErrorBoundary resetKey={location.pathname}>
              <Outlet />
            </ErrorBoundary>
          </main>
        </div>
      </div>
      {/* Authenticated-only by construction: AppLayout never renders on /login or /setup. */}
      <HelpChatLauncher />
    </div>
  );
}
