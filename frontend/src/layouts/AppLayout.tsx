import { useEffect, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Sidebar } from '@/components/common/Sidebar';
import { SetupProgressWidget } from '@/components/common/SetupProgressWidget';
import { Topbar } from '@/components/common/Topbar';
import { RealtimeBridge } from '@/realtime/RealtimeBridge';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { listPendingReviews, reviewKeys } from '@/api/reviews';
import './app-layout.css';

export function AppLayout() {
  const user = useAuthStore((s) => s.user);
  const edition = usePreferencesStore((s) => s.edition);
  const sidebarCollapsed = usePreferencesStore((s) => s.sidebarCollapsed);
  const toggleSidebar = usePreferencesStore((s) => s.toggleSidebar);
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();

  const isReviewer = user?.role === 'REVIEWER' || user?.role === 'ADMIN';
  const pendingFilters = { size: 1 };
  const { data } = useQuery({
    queryKey: reviewKeys.pendingFor(pendingFilters),
    queryFn: () => listPendingReviews(pendingFilters),
    enabled: !!user && isReviewer,
    refetchInterval: 30_000,
  });
  const pendingCount = data?.total_elements ?? 0;

  useEffect(() => {
    setMobileOpen(false);
  }, [location.pathname]);

  if (!user) return null;

  return (
    <div className="af-app-shell">
      <RealtimeBridge />
      <Sidebar
        user={user}
        edition={edition}
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
          <Outlet />
        </div>
      </div>
    </div>
  );
}
