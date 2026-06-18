import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { Organization } from '@/types/api';

const { listOrganizationsMock, createOrganizationMock, disableOrganizationMock, enableOrganizationMock } =
  vi.hoisted(() => ({
    listOrganizationsMock: vi.fn(),
    createOrganizationMock: vi.fn(),
    disableOrganizationMock: vi.fn(),
    enableOrganizationMock: vi.fn(),
  }));

vi.mock('@/api/organizations', async () => {
  const actual = await vi.importActual<typeof import('@/api/organizations')>('@/api/organizations');
  return {
    ...actual,
    listOrganizations: listOrganizationsMock,
    createOrganization: createOrganizationMock,
    disableOrganization: disableOrganizationMock,
    enableOrganization: enableOrganizationMock,
  };
});

const { OrganizationsListPage } = await import('./OrganizationsListPage');

function org(overrides: Partial<Organization> = {}): Organization {
  return {
    id: 'org-1',
    name: 'Acme',
    slug: 'acme',
    disabled: false,
    max_datasources: 5,
    max_users: null,
    max_queries_per_day: 1000,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function page(content: Organization[]) {
  return { content, page: 0, size: 100, total_elements: content.length, total_pages: 1 };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('OrganizationsListPage', () => {
  beforeEach(() => {
    listOrganizationsMock.mockReset();
    createOrganizationMock.mockReset();
    disableOrganizationMock.mockReset();
    enableOrganizationMock.mockReset();
  });

  it('lists organizations and renders quotas (unlimited for null)', async () => {
    listOrganizationsMock.mockResolvedValue(page([org()]));

    render(wrap(<OrganizationsListPage />));

    expect(await screen.findByText('Acme')).toBeInTheDocument();
    expect(screen.getByText('acme')).toBeInTheDocument();
    expect(screen.getByText('Enabled')).toBeInTheDocument();
    expect(screen.getByText('Unlimited')).toBeInTheDocument(); // max_users is null
  });

  it('opens the create modal', async () => {
    listOrganizationsMock.mockResolvedValue(page([]));

    render(wrap(<OrganizationsListPage />));

    await waitFor(() => expect(listOrganizationsMock).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: /Add organization/ }));

    expect(
      await screen.findByText('Create organization', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();
  });

  it('shows an Enable action for a disabled org', async () => {
    listOrganizationsMock.mockResolvedValue(page([org({ disabled: true })]));

    render(wrap(<OrganizationsListPage />));

    expect(await screen.findByText('Disabled')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Enable' })).toBeInTheDocument();
  });
});
