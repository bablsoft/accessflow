import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '@/i18n';
import type { AuthUser } from '@/api/auth';
import { SYSTEM_ROLE_PERMISSIONS } from '@/mocks/systemRolePermissions';
import { usePreferencesStore } from '@/store/preferencesStore';

const { listQueriesMock } = vi.hoisted(() => ({ listQueriesMock: vi.fn() }));

vi.mock('@/api/queries', async () => {
  const actual = await vi.importActual<typeof import('@/api/queries')>('@/api/queries');
  return { ...actual, listQueries: listQueriesMock };
});

const { CommandPalette } = await import('../CommandPalette');

// cmdk scrolls the selected item into view; jsdom has no implementation.
Element.prototype.scrollIntoView = vi.fn();

const admin: AuthUser = {
  id: 'u1',
  email: 'admin@example.com',
  display_name: 'Ada Admin',
  role: 'ADMIN',
  role_id: null,
  permissions: SYSTEM_ROLE_PERMISSIONS.ADMIN,
  auth_provider: 'LOCAL',
  totp_enabled: false,
  platform_admin: false,
  preferred_language: null,
};

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>;
}

function renderPalette(user: AuthUser = admin, open = true) {
  const onClose = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/dashboard']}>
        <Where />
        <CommandPalette open={open} onClose={onClose} user={user} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { onClose };
}

describe('CommandPalette', () => {
  beforeEach(() => {
    listQueriesMock.mockReset();
    listQueriesMock.mockResolvedValue({ content: [], page: 0, size: 5, total_elements: 0, total_pages: 0 });
    usePreferencesStore.setState({ navRecent: [], theme: 'light' });
  });

  it('renders nothing and fetches nothing while closed', () => {
    renderPalette(admin, false);
    expect(screen.queryByPlaceholderText('Search pages and actions…')).toBeNull();
    expect(listQueriesMock).not.toHaveBeenCalled();
  });

  it('lists the pages the user can see and navigates on select', () => {
    const { onClose } = renderPalette();
    const users = screen.getByText('Users').closest('[cmdk-item]') as HTMLElement;
    expect(users).toHaveTextContent('Identity & access › Identity');
    fireEvent.click(users);
    expect(onClose).toHaveBeenCalled();
    expect(screen.getByTestId('where')).toHaveTextContent('/admin/users');
  });

  it('filters by typed text and shows the empty state', () => {
    renderPalette();
    const input = screen.getByPlaceholderText('Search pages and actions…');
    fireEvent.change(input, { target: { value: 'audit log' } });
    expect(screen.getByText('Audit log')).toBeInTheDocument();
    expect(screen.queryByText('Datasources')).toBeNull();
    fireEvent.change(input, { target: { value: 'zzzzzz' } });
    expect(screen.getByText('No results')).toBeInTheDocument();
  });

  it('shows recent pages first and skips ids no longer visible', () => {
    usePreferencesStore.setState({ navRecent: ['audit', 'gone'] });
    renderPalette();
    const recent = screen.getByText('Recent').closest('[cmdk-group]') as HTMLElement;
    expect(within(recent).getByText('Audit log')).toBeInTheDocument();
    expect(within(recent).getAllByRole('option')).toHaveLength(1);
  });

  it('runs the theme and profile actions', () => {
    const { onClose } = renderPalette();
    fireEvent.click(screen.getByText('Switch theme'));
    expect(usePreferencesStore.getState().theme).toBe('dark');
    fireEvent.click(screen.getByText('Open profile'));
    expect(screen.getByTestId('where')).toHaveTextContent('/profile');
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('opens the editor from New query, and hides it without the permission', () => {
    renderPalette();
    fireEvent.click(screen.getByText('New query'));
    expect(screen.getByTestId('where')).toHaveTextContent('/editor');
  });

  it('offers recent queries and opens one', async () => {
    listQueriesMock.mockResolvedValue({
      content: [{ id: 'abcdef1234567890', status: 'EXECUTED', datasource: { id: 'd', name: 'orders-db' } }],
      page: 0, size: 5, total_elements: 1, total_pages: 1,
    });
    renderPalette();
    fireEvent.click(await screen.findByText('abcdef12'));
    expect(screen.getByTestId('where')).toHaveTextContent('/queries/abcdef1234567890');
  });

  it('skips New query and the query list for a user without those permissions', () => {
    renderPalette({ ...admin, role: 'READONLY', permissions: [] });
    expect(screen.queryByText('New query')).toBeNull();
    expect(listQueriesMock).not.toHaveBeenCalled();
  });
});
