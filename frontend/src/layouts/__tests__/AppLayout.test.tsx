import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { AuthUser } from '@/api/auth';
import type { PendingReviewsPage } from '@/types/api';

const { listPendingReviewsMock } = vi.hoisted(() => ({
  listPendingReviewsMock: vi.fn(),
}));

vi.mock('@/api/reviews', async () => {
  const actual = await vi.importActual<typeof import('@/api/reviews')>('@/api/reviews');
  return { ...actual, listPendingReviews: listPendingReviewsMock };
});

vi.mock('@/realtime/RealtimeBridge', () => ({
  RealtimeBridge: () => <div data-testid="realtime-bridge-sentinel" />,
}));

vi.mock('@/components/common/Sidebar', () => ({
  Sidebar: ({ pendingCount }: { pendingCount: number }) => (
    <aside data-testid="sidebar-mock" data-pending-count={pendingCount} />
  ),
}));

vi.mock('@/components/common/Topbar', () => ({
  Topbar: () => <header data-testid="topbar-mock" />,
}));

const { useAuthStore } = await import('@/store/authStore');
const { AppLayout } = await import('../AppLayout');

const reviewerUser: AuthUser = {
  id: 'u-1',
  email: 'reviewer@example.com',
  display_name: 'Test Reviewer',
  role: 'REVIEWER',
  preferred_language: null,
};

const adminUser: AuthUser = {
  id: 'u-2',
  email: 'admin@example.com',
  display_name: 'Test Admin',
  role: 'ADMIN',
  preferred_language: null,
};

const analystUser: AuthUser = {
  id: 'u-3',
  email: 'analyst@example.com',
  display_name: 'Test Analyst',
  role: 'ANALYST',
  preferred_language: null,
};

function emptyPage(): PendingReviewsPage {
  return { content: [], page: 0, size: 1, total_elements: 0, total_pages: 0 };
}

function pageWithTotal(total: number): PendingReviewsPage {
  return { content: [], page: 0, size: 1, total_elements: total, total_pages: 1 };
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
    listPendingReviewsMock.mockResolvedValue(emptyPage());
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

  it('feeds the sidebar badge from /reviews/pending total_elements for a REVIEWER', async () => {
    listPendingReviewsMock.mockResolvedValue(pageWithTotal(3));
    render(wrap(<AppLayout />));
    await waitFor(() => {
      expect(screen.getByTestId('sidebar-mock').dataset.pendingCount).toBe('3');
    });
    expect(listPendingReviewsMock).toHaveBeenCalledWith({ size: 1 });
  });

  it('feeds the sidebar badge from /reviews/pending for an ADMIN', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'jwt-test' });
    listPendingReviewsMock.mockResolvedValue(pageWithTotal(2));
    render(wrap(<AppLayout />));
    await waitFor(() => {
      expect(screen.getByTestId('sidebar-mock').dataset.pendingCount).toBe('2');
    });
  });

  it('does not call /reviews/pending for a non-reviewer role (no review nav, no 403)', async () => {
    useAuthStore.setState({ user: analystUser, accessToken: 'jwt-test' });
    render(wrap(<AppLayout />));
    expect(screen.getByTestId('sidebar-mock').dataset.pendingCount).toBe('0');
    // Give react-query a tick to confirm it didn't fire.
    await Promise.resolve();
    expect(listPendingReviewsMock).not.toHaveBeenCalled();
  });
});
