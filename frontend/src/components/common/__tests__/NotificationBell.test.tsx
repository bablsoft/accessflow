import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { UserNotificationPage } from '@/types/api';
import '@/i18n';

const {
  fetchUnreadCountMock,
  listNotificationsMock,
  markNotificationReadMock,
  markAllReadMock,
  deleteNotificationMock,
} = vi.hoisted(() => ({
  fetchUnreadCountMock: vi.fn(),
  listNotificationsMock: vi.fn(),
  markNotificationReadMock: vi.fn(),
  markAllReadMock: vi.fn(),
  deleteNotificationMock: vi.fn(),
}));

vi.mock('@/api/notifications', () => ({
  fetchUnreadCount: fetchUnreadCountMock,
  listNotifications: listNotificationsMock,
  markNotificationRead: markNotificationReadMock,
  markAllNotificationsRead: markAllReadMock,
  deleteNotification: deleteNotificationMock,
  notificationKeys: {
    all: ['notifications'],
    list: () => ['notifications', 'list'],
    unreadCount: () => ['notifications', 'unread-count'],
  },
}));

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const { NotificationBell } = await import('../NotificationBell');

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

function page(content: UserNotificationPage['content']): UserNotificationPage {
  return {
    content,
    page: 0,
    size: 20,
    total_elements: content.length,
    total_pages: content.length ? 1 : 0,
  };
}

describe('NotificationBell', () => {
  beforeEach(() => {
    fetchUnreadCountMock.mockReset();
    listNotificationsMock.mockReset();
    markNotificationReadMock.mockReset();
    markAllReadMock.mockReset();
    deleteNotificationMock.mockReset();
    navigateMock.mockReset();
  });

  it('shows the unread count badge', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 3 });
    listNotificationsMock.mockResolvedValue(page([]));

    render(wrap(<NotificationBell />));

    await waitFor(() => expect(screen.getByText('3')).toBeInTheDocument());
  });

  it('renders empty state when the list is empty after opening', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 0 });
    listNotificationsMock.mockResolvedValue(page([]));

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));

    await waitFor(() => {
      expect(screen.getByText('No notifications yet.')).toBeInTheDocument();
    });
  });

  it('clicking a QUERY_SUBMITTED row navigates to /reviews, not /queries/{id}', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    markNotificationReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'n2',
          event_type: 'QUERY_SUBMITTED',
          query_request_id: 'q-99',
          payload: { datasource: 'orders-prod', submitter: 'alice@acme.com' },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    const text = await screen.findByText(/orders-prod/);
    const row = text.closest('.ant-list-item');
    if (!row) throw new Error('list row not found');
    fireEvent.click(row);

    await waitFor(() => expect(markNotificationReadMock).toHaveBeenCalledWith('n2'));
    expect(navigateMock).toHaveBeenCalledWith('/reviews');
    expect(navigateMock).not.toHaveBeenCalledWith('/queries/q-99');
  });

  it('clicking an unread row marks it read and navigates to the linked query', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    markNotificationReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'n1',
          event_type: 'QUERY_APPROVED',
          query_request_id: 'q-42',
          payload: { datasource: 'orders-prod' },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    const text = await screen.findByText(/orders-prod/);
    const row = text.closest('.ant-list-item');
    if (!row) throw new Error('list row not found');
    fireEvent.click(row);

    await waitFor(() => expect(markNotificationReadMock).toHaveBeenCalledWith('n1'));
    expect(navigateMock).toHaveBeenCalledWith('/queries/q-42');
  });

  it('mark-all-read button calls markAllNotificationsRead when there are unread items', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 2 });
    markAllReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'n1',
          event_type: 'QUERY_APPROVED',
          query_request_id: null,
          payload: { datasource: 'A' },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    await waitFor(() => screen.getByText('Mark all as read'));

    fireEvent.click(screen.getByText('Mark all as read'));
    await waitFor(() => expect(markAllReadMock).toHaveBeenCalled());
  });

  it('delete button calls deleteNotification and does not navigate', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 0 });
    deleteNotificationMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'n7',
          event_type: 'QUERY_REJECTED',
          query_request_id: 'q-7',
          payload: { datasource: 'sales' },
          read: true,
          created_at: new Date().toISOString(),
          read_at: new Date().toISOString(),
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    await waitFor(() => screen.getByText(/sales/));

    fireEvent.click(screen.getByLabelText('Delete notification'));

    await waitFor(() => expect(deleteNotificationMock).toHaveBeenCalledWith('n7'));
    expect(navigateMock).not.toHaveBeenCalled();
  });
});
