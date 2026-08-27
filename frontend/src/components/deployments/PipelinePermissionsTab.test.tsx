import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type {
  DeploymentPipelineGroupPermission,
  DeploymentPipelinePermission,
  UserGroup,
  UserPage,
} from '@/types/api';

const {
  listDeploymentPermissions,
  listDeploymentGroupPermissions,
  grantDeploymentPermission,
  grantDeploymentGroupPermission,
  updateDeploymentPermission,
  updateDeploymentGroupPermission,
  revokeDeploymentPermission,
  revokeDeploymentGroupPermission,
  listUsers,
  listAllGroups,
} = vi.hoisted(() => ({
  listDeploymentPermissions: vi.fn(),
  listDeploymentGroupPermissions: vi.fn(),
  grantDeploymentPermission: vi.fn(),
  grantDeploymentGroupPermission: vi.fn(),
  updateDeploymentPermission: vi.fn(),
  updateDeploymentGroupPermission: vi.fn(),
  revokeDeploymentPermission: vi.fn(),
  revokeDeploymentGroupPermission: vi.fn(),
  listUsers: vi.fn(),
  listAllGroups: vi.fn(),
}));

vi.mock('@/api/deploymentPipelines', async () => {
  const actual = await vi.importActual<typeof import('@/api/deploymentPipelines')>(
    '@/api/deploymentPipelines',
  );
  return {
    ...actual,
    listDeploymentPermissions,
    listDeploymentGroupPermissions,
    grantDeploymentPermission,
    grantDeploymentGroupPermission,
    updateDeploymentPermission,
    updateDeploymentGroupPermission,
    revokeDeploymentPermission,
    revokeDeploymentGroupPermission,
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

const { PipelinePermissionsTab } = await import('./PipelinePermissionsTab');

const permission: DeploymentPipelinePermission = {
  id: 'perm-1',
  pipeline_id: 'pipe-1',
  user_id: 'u-1',
  user_email: 'alice@example.com',
  user_display_name: 'Alice',
  can_trigger: true,
  can_break_glass: false,
  expires_at: null,
  created_at: '2026-08-01T10:00:00Z',
};

const groupPermission: DeploymentPipelineGroupPermission = {
  id: 'gp-1',
  pipeline_id: 'pipe-1',
  group_id: 'g-1',
  group_name: 'Analysts',
  member_count: 4,
  can_trigger: true,
  can_break_glass: false,
  expires_at: null,
  created_at: '2026-08-01T10:00:00Z',
};

const users: UserPage = {
  content: [
    {
      id: 'u-1',
      email: 'alice@example.com',
      display_name: 'Alice',
      role: 'ANALYST',
      role_id: null,
      role_name: 'ANALYST',
      auth_provider: 'LOCAL',
      active: true,
      totp_enabled: false,
      last_login_at: null,
      preferred_language: 'en',
      created_at: '2026-08-01T10:00:00Z',
    },
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
      created_at: '2026-08-01T10:00:00Z',
    },
  ],
  page: 0,
  size: 100,
  total_elements: 2,
  total_pages: 1,
};

const groups: UserGroup[] = [
  {
    id: 'g-1',
    organization_id: 'org-1',
    name: 'Analysts',
    description: null,
    member_count: 4,
    created_at: '2026-08-01T10:00:00Z',
    updated_at: '2026-08-01T10:00:00Z',
  },
];

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

function selectOption(text: string) {
  const option = [...document.querySelectorAll('.ant-select-item-option-content')].find((o) =>
    o.textContent?.includes(text),
  );
  expect(option).toBeDefined();
  fireEvent.click(option!);
}

describe('PipelinePermissionsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listDeploymentPermissions.mockResolvedValue([permission]);
    listDeploymentGroupPermissions.mockResolvedValue([]);
    listUsers.mockResolvedValue(users);
    listAllGroups.mockResolvedValue(groups);
    grantDeploymentPermission.mockResolvedValue(permission);
    grantDeploymentGroupPermission.mockResolvedValue(groupPermission);
    updateDeploymentPermission.mockResolvedValue(permission);
    revokeDeploymentPermission.mockResolvedValue(undefined);
  });

  it('renders the user grants table and hides the group table when no group grants exist', async () => {
    render(wrap(<PipelinePermissionsTab pipelineId="pipe-1" />));

    await screen.findByText('User grants');
    expect(await screen.findByText('Alice (alice@example.com)')).toBeInTheDocument();
    // can_trigger true renders a check, can_break_glass false renders a dash.
    expect(screen.getByText('✓')).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.queryByText('Group grants')).not.toBeInTheDocument();
  });

  it('renders group grants with the member count when they exist', async () => {
    listDeploymentGroupPermissions.mockResolvedValue([groupPermission]);
    render(wrap(<PipelinePermissionsTab pipelineId="pipe-1" />));

    await screen.findByText('Group grants');
    expect(await screen.findByText('Analysts')).toBeInTheDocument();
    expect(screen.getByText('4 members')).toBeInTheDocument();
  });

  it('grants a user permission with the default capabilities and a null expiry', async () => {
    render(wrap(<PipelinePermissionsTab pipelineId="pipe-1" />));
    await screen.findByText('Alice (alice@example.com)');

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'User' }));
    await waitFor(() =>
      expect(
        [...document.querySelectorAll('.ant-select-item-option-content')].length,
      ).toBeGreaterThan(0),
    );
    selectOption('Bob (bob@example.com)');

    fireEvent.click(screen.getByRole('button', { name: 'Grant' }));

    await waitFor(() => expect(grantDeploymentPermission).toHaveBeenCalledTimes(1));
    const [pipelineId, input] = grantDeploymentPermission.mock.calls[0]!;
    expect(pipelineId).toBe('pipe-1');
    expect(input).toEqual({
      user_id: 'u-2',
      can_trigger: true,
      can_break_glass: false,
      expires_at: null,
    });
    expect(grantDeploymentGroupPermission).not.toHaveBeenCalled();
  });

  it('excludes already-granted users from the user selector', async () => {
    render(wrap(<PipelinePermissionsTab pipelineId="pipe-1" />));
    await screen.findByText('Alice (alice@example.com)');

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'User' }));
    await waitFor(() =>
      expect(
        [...document.querySelectorAll('.ant-select-item-option-content')].length,
      ).toBeGreaterThan(0),
    );
    const optionTexts = [...document.querySelectorAll('.ant-select-item-option-content')].map(
      (o) => o.textContent,
    );
    expect(optionTexts).toContain('Bob (bob@example.com)');
    expect(optionTexts).not.toContain('Alice (alice@example.com)');
  });

  it('switches the grant target to Group and grants through the group endpoint', async () => {
    render(wrap(<PipelinePermissionsTab pipelineId="pipe-1" />));
    await screen.findByText('Alice (alice@example.com)');

    expect(screen.getByRole('combobox', { name: 'User' })).toBeInTheDocument();
    fireEvent.click(screen.getByText('Group'));

    const groupSelect = await screen.findByRole('combobox', { name: 'Group' });
    expect(screen.queryByRole('combobox', { name: 'User' })).not.toBeInTheDocument();

    fireEvent.mouseDown(groupSelect);
    await waitFor(() =>
      expect(
        [...document.querySelectorAll('.ant-select-item-option-content')].length,
      ).toBeGreaterThan(0),
    );
    selectOption('Analysts');

    fireEvent.click(screen.getByRole('button', { name: 'Grant' }));

    await waitFor(() => expect(grantDeploymentGroupPermission).toHaveBeenCalledTimes(1));
    const [pipelineId, input] = grantDeploymentGroupPermission.mock.calls[0]!;
    expect(pipelineId).toBe('pipe-1');
    expect(input).toEqual({
      group_id: 'g-1',
      can_trigger: true,
      can_break_glass: false,
      expires_at: null,
    });
    expect(grantDeploymentPermission).not.toHaveBeenCalled();
  });

  it('edits a user grant via the edit modal and revokes via the revoke button', async () => {
    render(wrap(<PipelinePermissionsTab pipelineId="pipe-1" />));
    await screen.findByText('Alice (alice@example.com)');

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    await screen.findByText('Edit permission — Alice (alice@example.com)');

    const dialog = screen.getByRole('dialog');
    const switches = within(dialog).getAllByRole('switch');
    // can_trigger prefilled from the row.
    expect(switches[0]!.getAttribute('aria-checked')).toBe('true');
    // Toggle can_break_glass on.
    fireEvent.click(switches[1]!);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(updateDeploymentPermission).toHaveBeenCalledTimes(1));
    const [pipelineId, permissionId, payload] = updateDeploymentPermission.mock.calls[0]!;
    expect(pipelineId).toBe('pipe-1');
    expect(permissionId).toBe('perm-1');
    expect(payload).toEqual({ can_trigger: true, can_break_glass: true, expires_at: null });

    // Revoke is destructive — a Popconfirm guards it; nothing fires until confirmed.
    fireEvent.click(screen.getByRole('button', { name: 'Revoke' }));
    expect(revokeDeploymentPermission).not.toHaveBeenCalled();
    await screen.findByText('Revoke this grant?');
    const confirmButtons = screen.getAllByRole('button', { name: 'Revoke' });
    fireEvent.click(confirmButtons[confirmButtons.length - 1]!);
    await waitFor(() => expect(revokeDeploymentPermission).toHaveBeenCalledTimes(1));
    expect(revokeDeploymentPermission).toHaveBeenCalledWith('pipe-1', 'perm-1');
  });
});
