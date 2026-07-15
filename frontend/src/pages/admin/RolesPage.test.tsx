import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { PermissionCatalog, RoleSummary } from '@/types/api';

const {
  listRolesMock,
  getPermissionCatalogMock,
  createRoleMock,
  updateRoleMock,
  deleteRoleMock,
} = vi.hoisted(() => ({
  listRolesMock: vi.fn(),
  getPermissionCatalogMock: vi.fn(),
  createRoleMock: vi.fn(),
  updateRoleMock: vi.fn(),
  deleteRoleMock: vi.fn(),
}));

vi.mock('@/api/roles', async () => {
  const actual = await vi.importActual<typeof import('@/api/roles')>('@/api/roles');
  return {
    ...actual,
    listRoles: listRolesMock,
    getPermissionCatalog: getPermissionCatalogMock,
    createRole: createRoleMock,
    updateRole: updateRoleMock,
    deleteRole: deleteRoleMock,
  };
});

const { RolesPage } = await import('./RolesPage');

function role(partial: Partial<RoleSummary>): RoleSummary {
  return {
    id: 'r-x',
    organization_id: 'org-1',
    name: 'X',
    description: null,
    system: false,
    permissions: [],
    assigned_user_count: 0,
    created_at: '2026-07-01T00:00:00Z',
    updated_at: '2026-07-01T00:00:00Z',
    ...partial,
  };
}

function fixtures(): RoleSummary[] {
  return [
    role({
      id: 'r-admin',
      name: 'ADMIN',
      system: true,
      permissions: ['QUERY_SUBMIT_SELECT', 'USER_MANAGE', 'ROLE_MANAGE'],
      assigned_user_count: 2,
    }),
    role({
      id: 'r-custom',
      name: 'Release Manager',
      description: 'Reviews release queries',
      system: false,
      permissions: ['QUERY_REVIEW'],
      assigned_user_count: 1,
    }),
  ];
}

function catalog(): PermissionCatalog {
  return {
    groups: [
      { group: 'QUERIES', permissions: ['QUERY_SUBMIT_SELECT', 'QUERY_REVIEW'] },
      { group: 'USERS', permissions: ['USER_MANAGE', 'ROLE_MANAGE'] },
    ],
  };
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

describe('RolesPage', () => {
  beforeEach(() => {
    listRolesMock.mockReset();
    getPermissionCatalogMock.mockReset();
    createRoleMock.mockReset();
    updateRoleMock.mockReset();
    deleteRoleMock.mockReset();
    listRolesMock.mockResolvedValue(fixtures());
    getPermissionCatalogMock.mockResolvedValue(catalog());
  });

  it('renders the role table with localized system names, system tag, and counts', async () => {
    render(wrap(<RolesPage />));
    expect(await screen.findByText('Admin')).toBeInTheDocument();
    expect(screen.getByText('Release Manager')).toBeInTheDocument();
    expect(screen.getByText('System')).toBeInTheDocument();
    expect(screen.getByText('3 permissions')).toBeInTheDocument();
    expect(screen.getByText('1 permission')).toBeInTheDocument();
    expect(screen.getByText('2 users')).toBeInTheDocument();
    expect(screen.getByText('1 user')).toBeInTheDocument();
  });

  // Row-scoped role queries over the rendered AntD table are expensive under
  // jsdom; slow CI runners have pushed this past the default 15s timeout, so
  // it gets explicit headroom (it asserts state, not latency).
  it('disables edit and delete for system roles but not for custom roles', { timeout: 40_000 }, async () => {
    render(wrap(<RolesPage />));
    await screen.findByText('Admin');
    const adminRow = screen.getByText('Admin').closest('tr')!;
    const customRow = screen.getByText('Release Manager').closest('tr')!;
    expect(within(adminRow).getByRole('button', { name: 'Edit' })).toBeDisabled();
    expect(within(adminRow).getByRole('button', { name: 'Delete role' })).toBeDisabled();
    expect(within(customRow).getByRole('button', { name: 'Edit' })).toBeEnabled();
    expect(within(customRow).getByRole('button', { name: 'Delete role' })).toBeEnabled();
  });

  it('opens a read-only drawer for a system role', async () => {
    render(wrap(<RolesPage />));
    await screen.findByText('Admin');
    const rows = screen.getAllByRole('row');
    const adminRow = rows.find((r) => within(r).queryByText('Admin'))!;
    fireEvent.click(within(adminRow).getByRole('button', { name: 'View role' }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('View · ADMIN')).toBeInTheDocument();
    expect(within(drawer).getByLabelText('Name')).toBeDisabled();
    expect(within(drawer).queryByRole('button', { name: 'Save' })).toBeNull();
    await waitFor(() =>
      expect(within(drawer).getByRole('checkbox', { name: /Submit SELECT queries/ })).toBeChecked(),
    );
  });

  it('creates a role from the drawer with grouped permission checkboxes', async () => {
    createRoleMock.mockResolvedValue(fixtures()[1]);
    render(wrap(<RolesPage />));
    await screen.findByText('Admin');
    fireEvent.click(screen.getByRole('button', { name: /Create role/ }));
    const drawer = await screen.findByRole('dialog');
    await within(drawer).findByText('Queries');
    expect(within(drawer).getByText('Users')).toBeInTheDocument();

    fireEvent.change(within(drawer).getByLabelText('Name'), {
      target: { value: 'Release Manager' },
    });
    fireEvent.change(within(drawer).getByLabelText('Description'), {
      target: { value: 'Reviews release queries' },
    });
    fireEvent.click(within(drawer).getByRole('checkbox', { name: /Review queries/ }));
    fireEvent.click(within(drawer).getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(createRoleMock).toHaveBeenCalledWith({
        name: 'Release Manager',
        description: 'Reviews release queries',
        permissions: ['QUERY_REVIEW'],
      }),
    );
  });

  it('requires a name and at least one permission before submitting', async () => {
    render(wrap(<RolesPage />));
    await screen.findByText('Admin');
    fireEvent.click(screen.getByRole('button', { name: /Create role/ }));
    const drawer = await screen.findByRole('dialog');
    await within(drawer).findByText('Queries');
    fireEvent.click(within(drawer).getByRole('button', { name: 'Save' }));
    expect(await within(drawer).findByText('Role name is required.')).toBeInTheDocument();
    expect(
      await within(drawer).findByText('Select at least one permission.'),
    ).toBeInTheDocument();
    expect(createRoleMock).not.toHaveBeenCalled();
  });

  it('updates a custom role through the edit drawer', async () => {
    updateRoleMock.mockResolvedValue(fixtures()[1]);
    render(wrap(<RolesPage />));
    await screen.findByText('Release Manager');
    const rows = screen.getAllByRole('row');
    const customRow = rows.find((r) => within(r).queryByText('Release Manager'))!;
    fireEvent.click(within(customRow).getByRole('button', { name: 'Edit' }));
    const drawer = await screen.findByRole('dialog');
    await within(drawer).findByText('Queries');
    await waitFor(() =>
      expect(within(drawer).getByRole('checkbox', { name: /Review queries/ })).toBeChecked(),
    );
    fireEvent.click(within(drawer).getByRole('checkbox', { name: /Manage roles/ }));
    fireEvent.click(within(drawer).getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(updateRoleMock).toHaveBeenCalledWith('r-custom', {
        name: 'Release Manager',
        description: 'Reviews release queries',
        permissions: ['QUERY_REVIEW', 'ROLE_MANAGE'],
      }),
    );
  });

  it('deletes a custom role after confirmation', async () => {
    deleteRoleMock.mockResolvedValue(undefined);
    render(wrap(<RolesPage />));
    await screen.findByText('Release Manager');
    const rows = screen.getAllByRole('row');
    const customRow = rows.find((r) => within(r).queryByText('Release Manager'))!;
    fireEvent.click(within(customRow).getByRole('button', { name: 'Delete role' }));
    // AntD renders the confirm title twice (visible + wave/aria duplicate) — match all.
    await screen.findAllByText('Delete this role?');
    const confirmDialog = screen
      .getAllByRole('dialog')
      .find((d) => within(d).queryAllByText('Delete this role?').length > 0)!;
    fireEvent.click(within(confirmDialog).getByRole('button', { name: 'Delete role' }));
    await waitFor(() => expect(deleteRoleMock).toHaveBeenCalledWith('r-custom'));
  });
});
