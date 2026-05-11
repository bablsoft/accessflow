import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { SetupProgress } from '@/types/api';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import '@/i18n';

const { getSetupProgressMock } = vi.hoisted(() => ({
  getSetupProgressMock: vi.fn(),
}));

vi.mock('@/api/admin', () => ({
  getSetupProgress: getSetupProgressMock,
  setupProgressKeys: {
    all: ['setupProgress'],
    current: () => ['setupProgress', 'current'],
  },
}));

const { SetupProgressWidget } = await import('../SetupProgressWidget');

function wrap(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{node}</MemoryRouter>
    </QueryClientProvider>
  );
}

const adminUser = {
  id: 'u-admin',
  email: 'admin@example.com',
  display_name: 'Ada',
  role: 'ADMIN' as const,
  preferred_language: 'en',
};

const analystUser = {
  id: 'u-analyst',
  email: 'al@example.com',
  display_name: 'Al',
  role: 'ANALYST' as const,
  preferred_language: 'en',
};

const inProgress: SetupProgress = {
  datasources_configured: false,
  review_plans_configured: true,
  ai_provider_configured: false,
  completed_steps: 1,
  total_steps: 3,
  complete: false,
};

const completed: SetupProgress = {
  datasources_configured: true,
  review_plans_configured: true,
  ai_provider_configured: true,
  completed_steps: 3,
  total_steps: 3,
  complete: true,
};

describe('SetupProgressWidget', () => {
  beforeEach(() => {
    getSetupProgressMock.mockReset();
    useAuthStore.setState({ user: null, accessToken: null });
    usePreferencesStore.setState({ setupProgressCollapsed: false });
  });

  afterEach(() => {
    useAuthStore.setState({ user: null, accessToken: null });
  });

  it('renders nothing for non-admin users', () => {
    useAuthStore.setState({ user: analystUser, accessToken: 'tok' });
    getSetupProgressMock.mockResolvedValue(inProgress);

    const { container } = render(wrap(<SetupProgressWidget />));

    expect(container.firstChild).toBeNull();
    expect(getSetupProgressMock).not.toHaveBeenCalled();
  });

  it('renders nothing when setup is complete', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'tok' });
    getSetupProgressMock.mockResolvedValue(completed);

    const { container } = render(wrap(<SetupProgressWidget />));

    await waitFor(() => expect(getSetupProgressMock).toHaveBeenCalled());
    expect(container.firstChild).toBeNull();
  });

  it('renders the checklist with one step done and two pending', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'tok' });
    getSetupProgressMock.mockResolvedValue(inProgress);

    render(wrap(<SetupProgressWidget />));

    await screen.findByText(/finish setting up accessflow/i);
    expect(screen.getByText('1/3')).toBeInTheDocument();
    expect(screen.getByText(/add your first datasource/i)).toBeInTheDocument();
    expect(screen.getByText(/create a review plan/i)).toBeInTheDocument();
    expect(screen.getByText(/configure the ai provider/i)).toBeInTheDocument();

    // Two pending steps render a "Set up" button each.
    const setUpButtons = screen.getAllByRole('button', { name: /set up/i });
    expect(setUpButtons.length).toBe(2);
  });

  it('pending steps link to the right routes', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'tok' });
    getSetupProgressMock.mockResolvedValue(inProgress);

    render(wrap(<SetupProgressWidget />));
    await screen.findByText(/finish setting up accessflow/i);

    const datasourceLink = screen
      .getByText(/add your first datasource/i)
      .closest('li')
      ?.querySelector('a');
    expect(datasourceLink?.getAttribute('href')).toBe('/datasources/new');

    const aiLink = screen
      .getByText(/configure the ai provider/i)
      .closest('li')
      ?.querySelector('a');
    expect(aiLink?.getAttribute('href')).toBe('/admin/ai-config');
  });

  it('collapses the checklist when the toggle is clicked', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'tok' });
    getSetupProgressMock.mockResolvedValue(inProgress);

    render(wrap(<SetupProgressWidget />));
    await screen.findByText(/finish setting up accessflow/i);

    expect(screen.getByText(/add your first datasource/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /hide setup checklist/i }));

    expect(screen.queryByText(/add your first datasource/i)).toBeNull();
    // The expand button is now visible.
    expect(
      screen.getByRole('button', { name: /show setup checklist/i }),
    ).toBeInTheDocument();
  });
});
