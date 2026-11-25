import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { AuthUser } from '@/api/auth';
import type { PendingReviewsPage } from '@/types/api';
import { SYSTEM_ROLE_PERMISSIONS } from '@/mocks/systemRolePermissions';

const {
  listPendingReviewsMock,
  listPendingApiReviewsMock,
  listDeploymentReviewsMock,
  listDeploymentRollbackReviewsMock,
} = vi.hoisted(() => ({
  listPendingReviewsMock: vi.fn(),
  listPendingApiReviewsMock: vi.fn(),
  listDeploymentReviewsMock: vi.fn(),
  listDeploymentRollbackReviewsMock: vi.fn(),
}));

vi.mock('@/api/reviews', async () => {
  const actual = await vi.importActual<typeof import('@/api/reviews')>('@/api/reviews');
  return { ...actual, listPendingReviews: listPendingReviewsMock };
});

vi.mock('@/api/apiRequests', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiRequests')>('@/api/apiRequests');
  return { ...actual, listPendingApiReviews: listPendingApiReviewsMock };
});

vi.mock('@/api/deploymentReviews', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/deploymentReviews')>('@/api/deploymentReviews');
  return {
    ...actual,
    listDeploymentReviews: listDeploymentReviewsMock,
    listDeploymentRollbackReviews: listDeploymentRollbackReviewsMock,
  };
});

vi.mock('@/realtime/RealtimeBridge', () => ({
  RealtimeBridge: () => <div data-testid="realtime-bridge-sentinel" />,
}));

vi.mock('@/components/common/Sidebar', async () => {
  const actual =
    await vi.importActual<typeof import('@/components/common/Sidebar')>('@/components/common/Sidebar');
  return {
    ...actual,
    Sidebar: ({ pendingCount, collapsed }: { pendingCount: number; collapsed: boolean }) => (
      <aside
        data-testid="sidebar-mock"
        data-pending-count={pendingCount}
        data-collapsed={collapsed}
      />
    ),
  };
});

vi.mock('@/components/command/CommandPalette', () => ({
  CommandPalette: ({ open }: { open: boolean }) => (
    <div data-testid="palette-mock" data-open={open} />
  ),
}));

vi.mock('@/components/common/Topbar', () => ({
  Topbar: ({ onOpenCommandPalette }: { onOpenCommandPalette: () => void }) => (
    <header data-testid="topbar-mock">
      <button type="button" onClick={onOpenCommandPalette}>open-palette</button>
    </header>
  ),
}));

const { useAuthStore } = await import('@/store/authStore');
const { AppLayout } = await import('../AppLayout');

const reviewerUser: AuthUser = {
  id: 'u-1',
  email: 'reviewer@example.com',
  display_name: 'Test Reviewer',
  role: 'REVIEWER',
  role_id: null,
  permissions: SYSTEM_ROLE_PERMISSIONS.REVIEWER,
  auth_provider: 'LOCAL',
  totp_enabled: false,
  platform_admin: false,
  preferred_language: null,
};

const adminUser: AuthUser = {
  id: 'u-2',
  email: 'admin@example.com',
  display_name: 'Test Admin',
  role: 'ADMIN',
  role_id: null,
  permissions: SYSTEM_ROLE_PERMISSIONS.ADMIN,
  auth_provider: 'LOCAL',
  totp_enabled: false,
  platform_admin: false,
  preferred_language: null,
};

const analystUser: AuthUser = {
  id: 'u-3',
  email: 'analyst@example.com',
  display_name: 'Test Analyst',
  role: 'ANALYST',
  role_id: null,
  permissions: SYSTEM_ROLE_PERMISSIONS.ANALYST,
  auth_provider: 'LOCAL',
  totp_enabled: false,
  platform_admin: false,
  preferred_language: null,
};

function pageWithTotal(total: number): PendingReviewsPage {
  return { content: [], page: 0, size: 1, total_elements: total, total_pages: total > 0 ? 1 : 0 };
}

function emptyPage(): PendingReviewsPage {
  return pageWithTotal(0);
}

// Every queue's page envelope shares the `total_elements` shape the counts hook reads.
function allQueuesEmpty() {
  listPendingReviewsMock.mockResolvedValue(emptyPage());
  listPendingApiReviewsMock.mockResolvedValue(emptyPage());
  listDeploymentReviewsMock.mockResolvedValue(emptyPage());
  listDeploymentRollbackReviewsMock.mockResolvedValue(emptyPage());
}

function wrap(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/queries']}>
        <Routes>
          <Route element={node}>
            <Route path="/queries" element={<div data-testid="page" />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('AppLayout', () => {
  beforeEach(() => {
    listPendingReviewsMock.mockReset();
    listPendingApiReviewsMock.mockReset();
    listDeploymentReviewsMock.mockReset();
    listDeploymentRollbackReviewsMock.mockReset();
    allQueuesEmpty();
    useAuthStore.setState({ user: reviewerUser, accessToken: 'jwt-test' });
  });

  it('mounts RealtimeBridge so the WS connection is scoped to AuthGuard', () => {
    render(wrap(<AppLayout />));
    expect(screen.getByTestId('realtime-bridge-sentinel')).toBeInTheDocument();
  });

  it('renders the routed page through Outlet', () => {
    render(wrap(<AppLayout />));
    expect(screen.getByTestId('page')).toBeInTheDocument();
  });

  it('renders nothing while the user is unauthenticated', () => {
    useAuthStore.setState({ user: null, accessToken: null });
    const { container } = render(wrap(<AppLayout />));
    expect(container.querySelector('.af-app-shell')).toBeNull();
    expect(screen.queryByTestId('realtime-bridge-sentinel')).toBeNull();
  });

  it('feeds the sidebar badge from the sum of every queue a REVIEWER may work (#772)', async () => {
    listPendingReviewsMock.mockResolvedValue(pageWithTotal(3));
    listPendingApiReviewsMock.mockResolvedValue(pageWithTotal(2));
    listDeploymentReviewsMock.mockResolvedValue(pageWithTotal(1));
    listDeploymentRollbackReviewsMock.mockResolvedValue(pageWithTotal(1));
    render(wrap(<AppLayout />));
    await waitFor(() => {
      expect(screen.getByTestId('sidebar-mock').dataset.pendingCount).toBe('7');
    });
    // One `size=1` probe per queue — the count is the envelope's total_elements.
    expect(listPendingReviewsMock).toHaveBeenCalledWith({ size: 1 });
    expect(listPendingApiReviewsMock).toHaveBeenCalledWith({ size: 1 });
    expect(listDeploymentReviewsMock).toHaveBeenCalledWith({ size: 1 });
    expect(listDeploymentRollbackReviewsMock).toHaveBeenCalledWith({
      status: 'PENDING_REVIEW',
      size: 1,
    });
  });

  it('feeds the sidebar badge for an ADMIN', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'jwt-test' });
    listPendingReviewsMock.mockResolvedValue(pageWithTotal(2));
    render(wrap(<AppLayout />));
    await waitFor(() => {
      expect(screen.getByTestId('sidebar-mock').dataset.pendingCount).toBe('2');
    });
  });

  it('only probes the queues the user holds a review permission for', async () => {
    useAuthStore.setState({
      user: { ...analystUser, permissions: ['QUERY_SUBMIT_SELECT', 'DEPLOYMENT_REVIEW'] },
      accessToken: 'jwt-test',
    });
    listDeploymentReviewsMock.mockResolvedValue(pageWithTotal(4));
    render(wrap(<AppLayout />));
    await waitFor(() => {
      expect(screen.getByTestId('sidebar-mock').dataset.pendingCount).toBe('4');
    });
    expect(listPendingReviewsMock).not.toHaveBeenCalled();
    expect(listPendingApiReviewsMock).not.toHaveBeenCalled();
    expect(listDeploymentRollbackReviewsMock).toHaveBeenCalled();
  });

  it('does not probe any queue for a non-reviewer role (no review nav, no 403)', async () => {
    useAuthStore.setState({ user: analystUser, accessToken: 'jwt-test' });
    render(wrap(<AppLayout />));
    expect(screen.getByTestId('sidebar-mock').dataset.pendingCount).toBe('0');
    // Give react-query a tick to confirm it didn't fire.
    await Promise.resolve();
    expect(listPendingReviewsMock).not.toHaveBeenCalled();
    expect(listPendingApiReviewsMock).not.toHaveBeenCalled();
    expect(listDeploymentReviewsMock).not.toHaveBeenCalled();
    expect(listDeploymentRollbackReviewsMock).not.toHaveBeenCalled();
  });
});

describe('AppLayout — shell behaviour', () => {
  beforeEach(async () => {
    allQueuesEmpty();
    useAuthStore.setState({ user: adminUser, accessToken: 'jwt-test' });
    const { usePreferencesStore } = await import('@/store/preferencesStore');
    usePreferencesStore.setState({ navRecent: [], sidebarCollapsed: false });
  });

  it('toggles the command palette with Ctrl/Cmd+K and from the topbar', () => {
    render(wrap(<AppLayout />));
    const palette = () => screen.getByTestId('palette-mock').dataset.open;
    expect(palette()).toBe('false');
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    expect(palette()).toBe('true');
    fireEvent.keyDown(window, { key: 'K', metaKey: true });
    expect(palette()).toBe('false');
    fireEvent.keyDown(window, { key: 'k' });
    expect(palette()).toBe('false');
    fireEvent.click(screen.getByText('open-palette'));
    expect(palette()).toBe('true');
  });

  it('records the visited destination and wraps the page in the main landmark', async () => {
    const { usePreferencesStore } = await import('@/store/preferencesStore');
    render(wrap(<AppLayout />));
    expect(usePreferencesStore.getState().navRecent).toEqual(['queries']);
    expect(screen.getByRole('main')).toContainElement(screen.getByTestId('page'));
    expect(screen.getByText('Skip to content')).toHaveAttribute('href', '#main-content');
  });

  it('collapses the sidebar to a rail on a narrow desktop', () => {
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({
      matches: query.includes('max-width: 1024px'),
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia;
    try {
      render(wrap(<AppLayout />));
      expect(screen.getByTestId('sidebar-mock').dataset.collapsed).toBe('true');
    } finally {
      window.matchMedia = original;
    }
  });
});
