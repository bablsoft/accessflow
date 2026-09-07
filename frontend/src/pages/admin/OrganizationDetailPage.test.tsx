import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { Organization, OrganizationUsage } from '@/types/api';

const getOrganization = vi.fn();
const getOrganizationUsage = vi.fn();
const updateOrganization = vi.fn();

vi.mock('@/api/organizations', async (importActual) => {
  const actual = await importActual<typeof import('@/api/organizations')>();
  return {
    ...actual,
    getOrganization: (...args: unknown[]) => getOrganization(...args),
    getOrganizationUsage: (...args: unknown[]) => getOrganizationUsage(...args),
    updateOrganization: (...args: unknown[]) => updateOrganization(...args),
  };
});

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useParams: () => ({ id: 'org-1' }), useNavigate: () => vi.fn() };
});

const { OrganizationDetailPage } = await import('./OrganizationDetailPage');

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AntdApp>{node}</AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function org(overrides: Partial<Organization> = {}): Organization {
  return {
    id: 'org-1',
    name: 'Acme',
    slug: 'acme',
    disabled: false,
    max_datasources: 5,
    max_users: 20,
    max_queries_per_day: 1000,
    governs_apis: false,
    governs_deployments: false,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

const usage: OrganizationUsage = {
  organization_id: 'org-1',
  datasource_count: 1,
  max_datasources: 5,
  user_count: 2,
  max_users: 20,
  queries_last_24h: 3,
  max_queries_per_day: 1000,
};

describe('OrganizationDetailPage', () => {
  beforeEach(() => {
    getOrganization.mockReset();
    getOrganizationUsage.mockReset();
    updateOrganization.mockReset();
    getOrganizationUsage.mockResolvedValue(usage);
    updateOrganization.mockImplementation((_id: string, payload: Partial<Organization>) =>
      Promise.resolve(org(payload)),
    );
  });

  it('seeds the governance switches from the loaded organization', async () => {
    getOrganization.mockResolvedValue(org({ governs_apis: true, governs_deployments: false }));

    render(wrap(<OrganizationDetailPage />));

    await screen.findByLabelText('Govern outbound API calls');
    expect(screen.getByLabelText('Govern outbound API calls')).toBeChecked();
    expect(screen.getByLabelText('Gate CI/CD deployments')).not.toBeChecked();
  });

  it('sends both governance flags on save — this is the change-it-later path', async () => {
    getOrganization.mockResolvedValue(org({ governs_apis: false, governs_deployments: false }));

    render(wrap(<OrganizationDetailPage />));
    await screen.findByLabelText('Gate CI/CD deployments');

    fireEvent.click(screen.getByLabelText('Gate CI/CD deployments'));
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(updateOrganization).toHaveBeenCalledTimes(1));
    expect(updateOrganization.mock.calls[0]?.[1]).toMatchObject({
      name: 'Acme',
      governs_apis: false,
      governs_deployments: true,
    });
  });
});
