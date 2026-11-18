import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';

vi.mock('@/api/notifications', () => ({
  fetchUnreadCount: vi.fn().mockResolvedValue({ count: 0 }),
  listNotifications: vi.fn().mockResolvedValue({
    content: [],
    page: 0,
    size: 20,
    total_elements: 0,
    total_pages: 0,
  }),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
  deleteNotification: vi.fn(),
  notificationKeys: {
    all: ['notifications'],
    list: () => ['notifications', 'list'],
    unreadCount: () => ['notifications', 'unread-count'],
  },
}));

const { Topbar } = await import('../Topbar');
const { useAuthStore } = await import('@/store/authStore');
const { websocketManager } = await import('@/realtime/websocketManager');
const { SYSTEM_ROLE_PERMISSIONS } = await import('@/mocks/systemRolePermissions');

function wrap(node: ReactNode, route = '/') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>{node}</MemoryRouter>
    </QueryClientProvider>
  );
}

describe('Topbar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not render the search input', () => {
    render(wrap(<Topbar onOpenMobileNav={vi.fn()} onOpenCommandPalette={vi.fn()} />));
    expect(screen.queryByPlaceholderText(/search/i)).toBeNull();
  });

  it('does not render an edition selector', () => {
    render(wrap(<Topbar onOpenMobileNav={vi.fn()} onOpenCommandPalette={vi.fn()} />));
    expect(screen.queryByText(/community/i)).toBeNull();
    expect(screen.queryByText(/enterprise/i)).toBeNull();
  });

  it('renders the notification bell, user menu trigger, and theme toggle', () => {
    render(wrap(<Topbar onOpenMobileNav={vi.fn()} onOpenCommandPalette={vi.fn()} />));
    expect(screen.getByLabelText('Notifications')).toBeInTheDocument();
    expect(screen.getByLabelText('Open user menu')).toBeInTheDocument();
    expect(screen.getByLabelText('Light theme')).toBeInTheDocument();
    expect(screen.getByLabelText('Dark theme')).toBeInTheDocument();
  });
});

describe('Topbar — navigation aids', () => {
  beforeEach(() => {
    useAuthStore.setState({
      user: {
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
      },
    });
  });

  it('shows where the current page sits in the menu', () => {
    render(
      wrap(<Topbar onOpenMobileNav={vi.fn()} onOpenCommandPalette={vi.fn()} />, '/admin/users/42'),
    );
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(crumbs).toHaveTextContent('Identity & access');
    expect(crumbs).toHaveTextContent('Identity');
    const page = screen.getByRole('link', { name: 'Users' });
    expect(page).toHaveAttribute('href', '/admin/users');
    expect(page).not.toHaveAttribute('aria-current');
  });

  it('shows the page itself as plain current text and omits the trail off the menu', () => {
    const { unmount } = render(
      wrap(<Topbar onOpenMobileNav={vi.fn()} onOpenCommandPalette={vi.fn()} />, '/dashboard'),
    );
    expect(screen.getByText('Dashboard')).toHaveAttribute('aria-current', 'page');
    expect(screen.queryByRole('link', { name: 'Dashboard' })).toBeNull();
    unmount();
    render(wrap(<Topbar onOpenMobileNav={vi.fn()} onOpenCommandPalette={vi.fn()} />, '/profile'));
    expect(screen.queryByRole('navigation', { name: 'Breadcrumb' })).toBeNull();
  });

  it('opens the command palette from the search trigger', () => {
    const onOpen = vi.fn();
    render(wrap(<Topbar onOpenMobileNav={vi.fn()} onOpenCommandPalette={onOpen} />));
    fireEvent.click(screen.getByRole('button', { name: /Search/ }));
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('reflects the realtime connection state', () => {
    render(wrap(<Topbar onOpenMobileNav={vi.fn()} onOpenCommandPalette={vi.fn()} />));
    expect(screen.queryByRole('status')).toBeNull();
    const manager = websocketManager as unknown as { setStatus: (s: string) => void };
    act(() => manager.setStatus('reconnecting'));
    expect(screen.getByRole('status')).toHaveAccessibleName('Reconnecting to live updates…');
    act(() => manager.setStatus('open'));
    expect(screen.getByRole('status')).toHaveAccessibleName('Live updates connected');
    act(() => manager.setStatus('idle'));
  });
});
