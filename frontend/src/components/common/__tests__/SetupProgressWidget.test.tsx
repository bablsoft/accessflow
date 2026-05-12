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
  auth_provider: 'LOCAL' as const,
  totp_enabled: false,
  preferred_language: 'en',
};

const analystUser = {
  id: 'u-analyst',
  email: 'al@example.com',
  display_name: 'Al',
  role: 'ANALYST' as const,
  auth_provider: 'LOCAL' as const,
  totp_enabled: false,
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

const onlyAiMissing: SetupProgress = {
  datasources_configured: true,
  review_plans_configured: true,
  ai_provider_configured: false,
  completed_steps: 2,
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
    usePreferencesStore.setState({
      setupProgressCollapsed: false,
      setupProgressSkipped: [],
    });
  });

  afterEach(() => {
    useAuthStore.setState({ user: null, accessToken: null });
    usePreferencesStore.setState({ setupProgressSkipped: [] });
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

  it('lists steps with review plan first, then AI, then datasource', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'tok' });
    getSetupProgressMock.mockResolvedValue(inProgress);

    render(wrap(<SetupProgressWidget />));

    await screen.findByText(/finish setting up accessflow/i);
    const items = screen.getAllByRole('listitem');
    expect(items[0]?.textContent).toMatch(/create a review plan/i);
    expect(items[1]?.textContent).toMatch(/configure the ai provider/i);
    expect(items[2]?.textContent).toMatch(/add your first datasource/i);
  });

  it('renders progress bar matching one-of-three done', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'tok' });
    getSetupProgressMock.mockResolvedValue(inProgress);

    render(wrap(<SetupProgressWidget />));

    await screen.findByText(/finish setting up accessflow/i);
    expect(screen.getByText('1/3')).toBeInTheDocument();
    // Two pending steps render a "Set up" button each.
    expect(screen.getAllByRole('button', { name: /set up/i })).toHaveLength(2);
    // And a "Skip" button each.
    expect(screen.getAllByRole('button', { name: /^skip$/i })).toHaveLength(2);
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

  it('marks a step as Skipped when the Skip button is clicked', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'tok' });
    getSetupProgressMock.mockResolvedValue(onlyAiMissing);

    render(wrap(<SetupProgressWidget />));

    await screen.findByText(/finish setting up accessflow/i);
    // 2/3 done — one pending row with Set up + Skip buttons.
    fireEvent.click(screen.getByRole('button', { name: /^skip$/i }));

    // After skip, all three rows are effectively complete → widget hides.
    expect(screen.queryByText(/finish setting up accessflow/i)).toBeNull();
    expect(usePreferencesStore.getState().setupProgressSkipped).toEqual([
      'ai_provider',
    ]);
  });

  it('restores a skipped step when Undo skip is clicked', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'tok' });
    usePreferencesStore.setState({ setupProgressSkipped: ['ai_provider'] });
    getSetupProgressMock.mockResolvedValue(inProgress);

    render(wrap(<SetupProgressWidget />));

    await screen.findByText(/finish setting up accessflow/i);
    // Skipped row shows the Skipped tag and an Undo affordance.
    expect(screen.getByText(/^skipped$/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /undo skip/i }));

    expect(usePreferencesStore.getState().setupProgressSkipped).toEqual([]);
    // Pending again — Set up button reappears for that step.
    expect(
      screen
        .getByText(/configure the ai provider/i)
        .closest('li')
        ?.querySelector('a')
        ?.getAttribute('href'),
    ).toBe('/admin/ai-config');
  });

  it('counts skipped steps toward progress', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'tok' });
    usePreferencesStore.setState({ setupProgressSkipped: ['datasources'] });
    getSetupProgressMock.mockResolvedValue(inProgress);

    render(wrap(<SetupProgressWidget />));

    await screen.findByText(/finish setting up accessflow/i);
    // 1 done + 1 skipped + 1 pending → 2/3.
    expect(screen.getByText('2/3')).toBeInTheDocument();
  });

  it('collapses the checklist when the toggle is clicked', async () => {
    useAuthStore.setState({ user: adminUser, accessToken: 'tok' });
    getSetupProgressMock.mockResolvedValue(inProgress);

    render(wrap(<SetupProgressWidget />));
    await screen.findByText(/finish setting up accessflow/i);

    expect(screen.getByText(/add your first datasource/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /hide setup checklist/i }));

    expect(screen.queryByText(/add your first datasource/i)).toBeNull();
    expect(
      screen.getByRole('button', { name: /show setup checklist/i }),
    ).toBeInTheDocument();
  });
});
