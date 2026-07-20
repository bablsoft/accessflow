import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type {
  ApiConnectorGroupPermission,
  ApiConnectorPermission,
  ApiOperation,
  UserGroup,
  UserPage,
} from '@/types/api';

const {
  listApiConnectorPermissions,
  listApiConnectorGroupPermissions,
  listApiOperations,
  grantApiConnectorPermission,
  grantApiConnectorGroupPermission,
  updateApiConnectorPermission,
  updateApiConnectorGroupPermission,
  revokeApiConnectorPermission,
  revokeApiConnectorGroupPermission,
  listUsers,
  listAllGroups,
} = vi.hoisted(() => ({
  listApiConnectorPermissions: vi.fn(),
  listApiConnectorGroupPermissions: vi.fn(),
  listApiOperations: vi.fn(),
  grantApiConnectorPermission: vi.fn(),
  grantApiConnectorGroupPermission: vi.fn(),
  updateApiConnectorPermission: vi.fn(),
  updateApiConnectorGroupPermission: vi.fn(),
  revokeApiConnectorPermission: vi.fn(),
  revokeApiConnectorGroupPermission: vi.fn(),
  listUsers: vi.fn(),
  listAllGroups: vi.fn(),
}));

vi.mock('@/api/apiConnectors', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiConnectors')>('@/api/apiConnectors');
  return {
    ...actual,
    listApiConnectorPermissions,
    listApiConnectorGroupPermissions,
    listApiOperations,
    grantApiConnectorPermission,
    grantApiConnectorGroupPermission,
    updateApiConnectorPermission,
    updateApiConnectorGroupPermission,
    revokeApiConnectorPermission,
    revokeApiConnectorGroupPermission,
  };
});

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return { ...actual, listUsers };
});

vi.mock('@/api/groups', async () => {
  const actual = await vi.importActual<typeof import('@/api/groups')>('@/api/groups');
  return { ...actual, listAllGroups };
});

const { ApiConnectorPermissionsTab } = await import('./ApiConnectorPermissionsTab');

const permission: ApiConnectorPermission = {
  id: 'perm-1',
  user_id: 'u-1',
  user_email: 'alice@example.com',
  user_display_name: 'Alice',
  can_read: true,
  can_write: false,
  can_break_glass: false,
  can_override_variables: false,
  expires_at: null,
  allowed_operations: [],
  restricted_response_fields: [],
  created_at: '2026-05-04T10:15:00Z',
};

const operations: ApiOperation[] = [
  { operation_id: 'createPet', verb: 'POST', path: '/pets', summary: null, write: true },
];

const users: UserPage = {
  content: [
    {
      id: 'u-2',
      email: 'bob@example.com',
      display_name: 'Bob',
      role: 'ANALYST',
      role_id: null,
      role_name: 'ANALYST',
      auth_provider: 'LOCAL',
      active: true,
      totp_enabled: false,
      last_login_at: null,
      preferred_language: 'en',
      created_at: '2026-05-04T10:15:00Z',
    },
  ],
  page: 0,
  size: 100,
  total_elements: 1,
  total_pages: 1,
};

const groups: UserGroup[] = [
  {
    id: 'g-1',
    organization_id: 'org-1',
    name: 'Analysts',
    description: null,
    member_count: 4,
    created_at: '2026-05-04T10:15:00Z',
    updated_at: '2026-05-04T10:15:00Z',
  },
];

const groupPermission: ApiConnectorGroupPermission = {
  id: 'gp-1',
  connector_id: 'c-1',
  group_id: 'g-1',
  group_name: 'Analysts',
  member_count: 4,
  can_read: true,
  can_write: false,
  can_break_glass: false,
  can_override_variables: false,
  expires_at: null,
  allowed_operations: [],
  restricted_response_fields: [],
  created_at: '2026-05-04T10:15:00Z',
};

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

describe('ApiConnectorPermissionsTab — edit flow', () => {
  beforeEach(() => {
    listApiConnectorPermissions.mockResolvedValue([permission]);
    listApiConnectorGroupPermissions.mockResolvedValue([]);
    listApiOperations.mockResolvedValue(operations);
    listUsers.mockResolvedValue(users);
    listAllGroups.mockResolvedValue(groups);
    grantApiConnectorGroupPermission.mockResolvedValue(groupPermission);
    updateApiConnectorPermission.mockResolvedValue({ ...permission, can_write: true });
    updateApiConnectorGroupPermission.mockResolvedValue({ ...groupPermission, can_write: true });
  });

  it('opens a pre-filled edit modal and submits an update via PUT', async () => {
    render(wrap(<ApiConnectorPermissionsTab connectorId="c-1" />));

    // Row rendered
    await screen.findByText('alice@example.com');

    // Open edit modal
    fireEvent.click(screen.getByRole('button', { name: /edit/i }));

    // Modal title mentions the subject
    await screen.findByText(/Edit permission — Alice/i);

    // Read switch pre-filled to checked (permission.can_read = true)
    const dialog = screen.getByRole('dialog');
    const switches = Array.from(
      dialog.querySelectorAll<HTMLElement>('button[role="switch"]'),
    );
    expect(switches.length).toBeGreaterThanOrEqual(3);
    const readSwitch = switches[0]!;
    const writeSwitch = switches[1]!;
    // can_read is the first switch and should be checked
    expect(readSwitch.getAttribute('aria-checked')).toBe('true');

    // Toggle can_write on (second switch)
    fireEvent.click(writeSwitch);

    // Save
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(updateApiConnectorPermission).toHaveBeenCalledTimes(1));
    const [connectorId, permissionId, payload] = updateApiConnectorPermission.mock.calls[0]!;
    expect(connectorId).toBe('c-1');
    expect(permissionId).toBe('perm-1');
    expect(payload.can_write).toBe(true);
    expect(payload.can_read).toBe(true);
  });

  it('switching the grant target to Group swaps the user selector for a group selector', async () => {
    render(wrap(<ApiConnectorPermissionsTab connectorId="c-1" />));
    await screen.findByText('alice@example.com');

    // The user selector is shown by default.
    expect(screen.getByRole('combobox', { name: 'User' })).toBeInTheDocument();

    // Flip the Segmented toggle to Group (click the segmented option label).
    fireEvent.click(screen.getByText('Group'));

    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: 'Group' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('combobox', { name: 'User' })).not.toBeInTheDocument();
  });

  it('renders group grants in their own section with the member count', async () => {
    listApiConnectorGroupPermissions.mockResolvedValue([groupPermission]);
    render(wrap(<ApiConnectorPermissionsTab connectorId="c-1" />));

    await screen.findByText('Group grants');
    expect(await screen.findByText(/Analysts/)).toBeInTheDocument();
    expect(screen.getByText('4 members')).toBeInTheDocument();
  });

  it('grant button label tracks the selected target', async () => {
    render(wrap(<ApiConnectorPermissionsTab connectorId="c-1" />));
    await screen.findByText('alice@example.com');

    // Default target is user.
    expect(screen.getByRole('button', { name: 'Share with user' })).toBeInTheDocument();

    // Flip to Group — the submit label follows.
    fireEvent.click(screen.getByText('Group'));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Share with group' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'Share with user' })).not.toBeInTheDocument();
  });

  it('edits a group grant via the group PUT endpoint', async () => {
    listApiConnectorGroupPermissions.mockResolvedValue([groupPermission]);
    render(wrap(<ApiConnectorPermissionsTab connectorId="c-1" />));

    // Wait for both the user row and the group row to render.
    await screen.findByText('alice@example.com');
    await screen.findByText(/Analysts/);

    // Both tables expose an Edit action; the group row's is the last one.
    const editButtons = await screen.findAllByRole('button', { name: /edit/i });
    expect(editButtons.length).toBeGreaterThanOrEqual(2);
    fireEvent.click(editButtons[editButtons.length - 1]!);

    // Group edit modal titled by the group name.
    await screen.findByText(/Edit group permission — Analysts/i);

    const dialog = screen.getByRole('dialog');
    const switches = Array.from(dialog.querySelectorAll<HTMLElement>('button[role="switch"]'));
    // Toggle can_write (second switch) on.
    fireEvent.click(switches[1]!);

    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(updateApiConnectorGroupPermission).toHaveBeenCalledTimes(1));
    const [connectorId, permissionId, payload] =
      updateApiConnectorGroupPermission.mock.calls[0]!;
    expect(connectorId).toBe('c-1');
    expect(permissionId).toBe('gp-1');
    expect(payload.can_write).toBe(true);
  });
});
