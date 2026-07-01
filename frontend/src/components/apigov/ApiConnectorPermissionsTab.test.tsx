import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ApiConnectorPermission, ApiOperation, UserPage } from '@/types/api';

const {
  listApiConnectorPermissions,
  listApiOperations,
  grantApiConnectorPermission,
  updateApiConnectorPermission,
  revokeApiConnectorPermission,
  listUsers,
} = vi.hoisted(() => ({
  listApiConnectorPermissions: vi.fn(),
  listApiOperations: vi.fn(),
  grantApiConnectorPermission: vi.fn(),
  updateApiConnectorPermission: vi.fn(),
  revokeApiConnectorPermission: vi.fn(),
  listUsers: vi.fn(),
}));

vi.mock('@/api/apiConnectors', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiConnectors')>('@/api/apiConnectors');
  return {
    ...actual,
    listApiConnectorPermissions,
    listApiOperations,
    grantApiConnectorPermission,
    updateApiConnectorPermission,
    revokeApiConnectorPermission,
  };
});

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return { ...actual, listUsers };
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
  expires_at: null,
  allowed_operations: [],
  restricted_response_fields: [],
  created_at: '2026-05-04T10:15:00Z',
};

const operations: ApiOperation[] = [
  { operation_id: 'createPet', verb: 'POST', path: '/pets', summary: null, write: true },
];

const users: UserPage = {
  content: [],
  page: 0,
  size: 100,
  total_elements: 0,
  total_pages: 0,
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
    listApiOperations.mockResolvedValue(operations);
    listUsers.mockResolvedValue(users);
    updateApiConnectorPermission.mockResolvedValue({ ...permission, can_write: true });
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
});
