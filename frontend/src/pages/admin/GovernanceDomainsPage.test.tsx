import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { GovernanceDomainsConfig } from '@/types/api';

const { getDomains, updateDomains } = vi.hoisted(() => ({
  getDomains: vi.fn(),
  updateDomains: vi.fn(),
}));

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return { ...actual, getGovernanceDomains: getDomains, updateGovernanceDomains: updateDomains };
});

import { useAuthStore } from '@/store/authStore';

const { GovernanceDomainsPage } = await import('./GovernanceDomainsPage');

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter>{children}</MemoryRouter>
      </App>
    </QueryClientProvider>
  );
  return render(<GovernanceDomainsPage />, { wrapper });
}

const both: GovernanceDomainsConfig = { governs_apis: true, governs_deployments: true };

describe('GovernanceDomainsPage (#926)', () => {
  beforeEach(() => {
    getDomains.mockReset();
    updateDomains.mockReset();
    useAuthStore.setState({
      user: {
        id: 'u-1',
        email: 'admin@x.io',
        display_name: 'Admin',
        role: 'ADMIN',
        role_id: null,
        permissions: ['SETUP_PROGRESS_VIEW'],
        auth_provider: 'LOCAL',
        totp_enabled: false,
        platform_admin: false,
        preferred_language: null,
        governs_apis: true,
        governs_deployments: true,
      },
      accessToken: 't',
    });
  });

  it('renders both switches checked for an organization governing both domains', async () => {
    getDomains.mockResolvedValue(both);
    renderPage();

    const apis = await screen.findByRole('switch', { name: 'Govern outbound API calls' });
    expect(apis).toBeChecked();
    expect(screen.getByRole('switch', { name: 'Gate CI/CD deployments' })).toBeChecked();
  });

  it('saves the toggled flags and pushes them into the cached session user', async () => {
    getDomains.mockResolvedValue(both);
    updateDomains.mockResolvedValue({ governs_apis: false, governs_deployments: true });
    renderPage();

    fireEvent.click(await screen.findByRole('switch', { name: 'Govern outbound API calls' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(updateDomains).toHaveBeenCalledWith({
        governs_apis: false,
        governs_deployments: true,
      }),
    );
    // The nav must react on this render, not at the next token refresh.
    await waitFor(() => expect(useAuthStore.getState().user?.governs_apis).toBe(false));
    expect(useAuthStore.getState().user?.governs_deployments).toBe(true);
  });

  it('shows the load error when the read fails', async () => {
    getDomains.mockRejectedValue(new Error('boom'));
    renderPage();
    expect(await screen.findByText('Could not load the governance domains')).toBeInTheDocument();
  });
});
