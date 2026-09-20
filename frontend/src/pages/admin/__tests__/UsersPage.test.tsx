import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { User } from '@/types/api';

const listUsers = vi.fn();
const listInvitations = vi.fn();

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return {
    ...actual,
    listUsers: (...args: unknown[]) => listUsers(...args),
    listInvitations: (...args: unknown[]) => listInvitations(...args),
  };
});

vi.mock('@/api/roles', () => ({
  listRoles: () =>
    Promise.resolve([
      {
        id: 'r-1',
        organization_id: 'org-1',
        name: 'ANALYST',
        description: null,
        system: true,
        permissions: [],
        assigned_user_count: 1,
        created_at: '2026-01-01T00:00:00Z',
        updated_at: '2026-01-01T00:00:00Z',
      },
    ]),
  roleKeys: { all: ['roles'] as const, lists: () => ['roles', 'list'] as const },
}));

const { UsersPage } = await import('../UsersPage');

function user(partial: Partial<User>): User {
  return {
    id: 'u-1',
    email: 'alice@example.com',
    display_name: 'Alice',
    role: 'ANALYST',
    role_id: 'r-1',
    role_name: 'ANALYST',
    auth_provider: 'LOCAL',
    active: true,
    totp_enabled: false,
    last_login_at: null,
    preferred_language: null,
    created_at: '2026-01-01T00:00:00Z',
    principal_type: 'HUMAN',
    ...partial,
  };
}

function page(content: User[]) {
  return { content, page: 0, size: 20, total_elements: content.length, total_pages: 1 };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/users']}>
        <AntdApp>
          <Routes>
            <Route path="/admin/users" element={node} />
            <Route path="/admin/service-accounts/:id" element={<div>service-account-route</div>} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('UsersPage — service accounts (#875)', () => {
  beforeEach(() => {
    listUsers.mockReset();
    listInvitations.mockReset();
    listInvitations.mockResolvedValue({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });
    listUsers.mockResolvedValue(
      page([
        user({}),
        user({ id: 'sa-1', email: 'ci-bot@example.com', display_name: 'CI bot', principal_type: 'SERVICE_ACCOUNT' }),
      ]),
    );
  });

  it('lists people and service accounts together by default, badging the agents', async () => {
    render(wrap(<UsersPage />));

    const botRow = (await screen.findByText('CI bot')).closest('tr');
    expect(within(botRow!).getByTestId('principal-type-tag')).toHaveTextContent('Service account');
    const aliceRow = screen.getByText('Alice').closest('tr');
    expect(within(aliceRow!).queryByTestId('principal-type-tag')).not.toBeInTheDocument();
    expect(listUsers).toHaveBeenCalledWith({ page: 0, size: 20, sort: 'email,asc' });
    expect(screen.queryByText('SERVICE_ACCOUNT')).not.toBeInTheDocument();
  });

  it('filters by principal type server-side', async () => {
    render(wrap(<UsersPage />));
    await screen.findByText('CI bot');

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'People and service accounts' }));
    fireEvent.click(await screen.findByTitle('Service accounts only'));

    await waitFor(() =>
      expect(listUsers).toHaveBeenCalledWith({
        page: 0,
        size: 20,
        sort: 'email,asc',
        principal_type: 'SERVICE_ACCOUNT',
      }),
    );
  });

  it('routes a service account to its own page instead of the user edit modal', async () => {
    render(wrap(<UsersPage />));
    const botRow = (await screen.findByText('CI bot')).closest('tr');

    fireEvent.click(within(botRow!).getByRole('button', { name: 'Edit' }));
    fireEvent.click(await screen.findByText('Manage this identity from Service accounts.'));

    expect(await screen.findByText('service-account-route')).toBeInTheDocument();
  });
});
