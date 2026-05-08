import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { AuthUser } from '@/api/auth';

const { listQueriesMock } = vi.hoisted(() => ({
  listQueriesMock: vi.fn(),
}));

vi.mock('@/api/queries', async () => {
  const actual = await vi.importActual<typeof import('@/api/queries')>('@/api/queries');
  return { ...actual, listQueries: listQueriesMock };
});

vi.mock('@/realtime/RealtimeBridge', () => ({
  RealtimeBridge: () => <div data-testid="realtime-bridge-sentinel" />,
}));

vi.mock('@/components/common/Sidebar', () => ({
  Sidebar: () => <aside data-testid="sidebar-mock" />,
}));

vi.mock('@/components/common/Topbar', () => ({
  Topbar: () => <header data-testid="topbar-mock" />,
}));

const { useAuthStore } = await import('@/store/authStore');
const { AppLayout } = await import('../AppLayout');

const authedUser: AuthUser = {
  id: 'u-1',
  email: 'reviewer@example.com',
  display_name: 'Test Reviewer',
  role: 'REVIEWER',
};

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
    listQueriesMock.mockResolvedValue({ items: [], total_elements: 0, page: 0, size: 1 });
    useAuthStore.setState({ user: authedUser, accessToken: 'jwt-test' });
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
});
