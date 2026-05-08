import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
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

function wrap(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{node}</MemoryRouter>
    </QueryClientProvider>
  );
}

describe('Topbar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not render the search input', () => {
    render(wrap(<Topbar onOpenMobileNav={vi.fn()} />));
    expect(screen.queryByPlaceholderText(/search/i)).toBeNull();
  });

  it('does not render the community/enterprise edition selector', () => {
    render(wrap(<Topbar onOpenMobileNav={vi.fn()} />));
    expect(screen.queryByText(/community/i)).toBeNull();
    expect(screen.queryByText(/enterprise/i)).toBeNull();
  });

  it('renders the notification bell, sign-out button, and theme toggle', () => {
    render(wrap(<Topbar onOpenMobileNav={vi.fn()} />));
    expect(screen.getByLabelText('Notifications')).toBeInTheDocument();
    expect(screen.getByLabelText('Sign out')).toBeInTheDocument();
    expect(screen.getByLabelText('Light theme')).toBeInTheDocument();
    expect(screen.getByLabelText('Dark theme')).toBeInTheDocument();
  });
});
