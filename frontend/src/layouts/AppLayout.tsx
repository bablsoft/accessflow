import { useEffect, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Sidebar } from '@/components/common/Sidebar';
import { Topbar } from '@/components/common/Topbar';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { listQueries, queryKeys } from '@/api/queries';
import './app-layout.css';

export function AppLayout() {
  const user = useAuthStore((s) => s.user);
  const edition = usePreferencesStore((s) => s.edition);
  const sidebarCollapsed = usePreferencesStore((s) => s.sidebarCollapsed);
  const toggleSidebar = usePreferencesStore((s) => s.toggleSidebar);
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();

  const pendingFilters = { status: 'PENDING_REVIEW' as const, page: 0, size: 1 };
  const { data } = useQuery({
    queryKey: queryKeys.list(pendingFilters),
    queryFn: () => listQueries(pendingFilters),
    enabled: !!user,
    refetchInterval: 30_000,
  });
  const pendingCount = data?.total_elements ?? 0;

  useEffect(() => {
    setMobileOpen(false);
  }, [location.pathname]);

  if (!user) return null;

  return (
    <div className="af-app-shell">
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
          <Outlet />
        </div>
      </div>
    </div>
  );
}
