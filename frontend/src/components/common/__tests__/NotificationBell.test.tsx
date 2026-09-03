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
  deleteAllNotificationsMock,
} = vi.hoisted(() => ({
  fetchUnreadCountMock: vi.fn(),
  listNotificationsMock: vi.fn(),
  markNotificationReadMock: vi.fn(),
  markAllReadMock: vi.fn(),
  deleteNotificationMock: vi.fn(),
  deleteAllNotificationsMock: vi.fn(),
}));

vi.mock('@/api/notifications', () => ({
  fetchUnreadCount: fetchUnreadCountMock,
  listNotifications: listNotificationsMock,
  markNotificationRead: markNotificationReadMock,
  markAllNotificationsRead: markAllReadMock,
  deleteNotification: deleteNotificationMock,
  deleteAllNotifications: deleteAllNotificationsMock,
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
    deleteAllNotificationsMock.mockReset();
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
          api_request_id: null,
          deployment_request_id: null,
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
    expect(navigateMock).toHaveBeenCalledWith('/reviews?tab=queries');
    expect(navigateMock).not.toHaveBeenCalledWith('/queries/q-99');
  });

  it.each([
    ['REVIEW_ESCALATED' as const, null, '/reviews?tab=queries'],
    ['REVIEW_ESCALATED' as const, 'api-77', '/reviews?tab=api'],
    ['REVIEW_NUDGE' as const, null, '/reviews?tab=queries'],
    ['REVIEW_NUDGE' as const, 'api-88', '/reviews?tab=api'],
  ])(
    'routes a %s row by request kind, not by event name (api_request_id=%s)',
    async (eventType, apiRequestId, expected) => {
      // #622 — these are the only two event types raised for BOTH queries and API requests, so
      // the event name alone cannot pick the queue. Sending a stalled API request to /reviews
      // lands the reviewer where it does not appear.
      fetchUnreadCountMock.mockResolvedValue({ count: 1 });
      markNotificationReadMock.mockResolvedValue(undefined);
      listNotificationsMock.mockResolvedValue(
        page([
          {
            id: 'n-esc',
            event_type: eventType,
            query_request_id: apiRequestId ? null : 'q-55',
            api_request_id: apiRequestId,
            deployment_request_id: null,
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

      await waitFor(() => expect(navigateMock).toHaveBeenCalledWith(expected));
    },
  );

  it('clicking an unread row marks it read and navigates to the linked query', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    markNotificationReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'n1',
          event_type: 'QUERY_APPROVED',
          query_request_id: 'q-42',
          api_request_id: null,
          deployment_request_id: null,
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
          api_request_id: null,
          deployment_request_id: null,
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

  it('renders an API_REQUEST_SUBMITTED body and navigates to the API tab of the review queue', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    markNotificationReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'a1',
          event_type: 'API_REQUEST_SUBMITTED',
          query_request_id: null,
          api_request_id: 'api-11',
          deployment_request_id: null,
          payload: { datasource: 'payments-api', submitter: 'bob@acme.com' },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    const text = await screen.findByText(/submitted an API request on payments-api/);
    const row = text.closest('.ant-list-item');
    if (!row) throw new Error('list row not found');
    fireEvent.click(row);

    await waitFor(() => expect(markNotificationReadMock).toHaveBeenCalledWith('a1'));
    expect(navigateMock).toHaveBeenCalledWith('/reviews?tab=api');
    expect(navigateMock).not.toHaveBeenCalledWith('/api-requests/api-11');
  });

  it('renders a terminal API event body and navigates to the API-request detail page', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    markNotificationReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'a2',
          event_type: 'API_REQUEST_EXECUTED',
          query_request_id: null,
          api_request_id: 'api-22',
          deployment_request_id: null,
          payload: { datasource: 'payments-api' },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    const text = await screen.findByText(/API request on payments-api was executed/);
    const row = text.closest('.ant-list-item');
    if (!row) throw new Error('list row not found');
    fireEvent.click(row);

    await waitFor(() => expect(markNotificationReadMock).toHaveBeenCalledWith('a2'));
    expect(navigateMock).toHaveBeenCalledWith('/api-requests/api-22');
  });

  it('renders an ACCESS_REQUEST_SUBMITTED body and navigates to /admin/access-requests', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    markNotificationReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'ar1',
          event_type: 'ACCESS_REQUEST_SUBMITTED',
          query_request_id: null,
          api_request_id: null,
          deployment_request_id: null,
          payload: {
            access_request_id: 'req-1',
            datasource: 'orders-prod',
            requester: 'carol@acme.com',
            requested_duration: 'PT4H',
            status: 'PENDING',
          },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    const text = await screen.findByText(/carol@acme\.com requested access to orders-prod/);
    const row = text.closest('.ant-list-item');
    if (!row) throw new Error('list row not found');
    fireEvent.click(row);

    await waitFor(() => expect(markNotificationReadMock).toHaveBeenCalledWith('ar1'));
    expect(navigateMock).toHaveBeenCalledWith('/admin/access-requests');
  });

  it('renders the no-requester variant when the ACCESS_REQUEST_SUBMITTED payload has no requester', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'ar2',
          event_type: 'ACCESS_REQUEST_SUBMITTED',
          query_request_id: null,
          api_request_id: null,
          deployment_request_id: null,
          payload: { access_request_id: 'req-2', datasource: 'orders-prod' },
          read: true,
          created_at: new Date().toISOString(),
          read_at: new Date().toISOString(),
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));

    await waitFor(() => {
      expect(
        screen.getByText('New access request awaiting review on orders-prod'),
      ).toBeInTheDocument();
    });
  });

  it.each([
    ['ACCESS_REQUEST_APPROVED', /access request for hr-db was approved/],
    ['ACCESS_REQUEST_REJECTED', /access request for hr-db was rejected/],
    ['ACCESS_GRANT_EXPIRED', /access grant on hr-db expired/],
    ['ACCESS_GRANT_REVOKED', /access grant on hr-db was revoked/],
  ] as const)('renders %s and navigates to /access-requests', async (eventType, expected) => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    markNotificationReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'ar3',
          event_type: eventType,
          query_request_id: null,
          api_request_id: null,
          deployment_request_id: null,
          payload: { access_request_id: 'req-3', datasource: 'hr-db' },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    const text = await screen.findByText(expected);
    const row = text.closest('.ant-list-item');
    if (!row) throw new Error('list row not found');
    fireEvent.click(row);

    await waitFor(() => expect(markNotificationReadMock).toHaveBeenCalledWith('ar3'));
    expect(navigateMock).toHaveBeenCalledWith('/access-requests');
  });

  it('renders SENSITIVE_RESULT_EXPORTED with classifications and navigates to the query', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    markNotificationReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'sre1',
          event_type: 'SENSITIVE_RESULT_EXPORTED',
          query_request_id: 'q-626',
          api_request_id: null,
          deployment_request_id: null,
          payload: {
            datasource: 'billing-prod',
            submitter: 'analyst@acme.com',
            export_classifications: 'PCI, PHI',
            export_format: 'CSV',
          },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    const text = await screen.findByText(/Sensitive data \(PCI, PHI\) exported from billing-prod/);
    const row = text.closest('.ant-list-item');
    if (!row) throw new Error('list row not found');
    fireEvent.click(row);

    await waitFor(() => expect(markNotificationReadMock).toHaveBeenCalledWith('sre1'));
    expect(navigateMock).toHaveBeenCalledWith('/queries/q-626');
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
          api_request_id: null,
          deployment_request_id: null,
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

  it('hides the delete-all button when the list is empty', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 0 });
    listNotificationsMock.mockResolvedValue(page([]));

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));

    await waitFor(() => expect(screen.getByText('No notifications yet.')).toBeInTheDocument());
    expect(screen.queryByText('Delete all')).not.toBeInTheDocument();
  });

  it('delete-all confirms, calls deleteAllNotifications, and refetches the list', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    deleteAllNotificationsMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'n8',
          event_type: 'QUERY_APPROVED',
          query_request_id: 'q-8',
          api_request_id: null,
          deployment_request_id: null,
          payload: { datasource: 'sales' },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    await waitFor(() => screen.getByText('Delete all'));

    // The mutation only fires after the Popconfirm is confirmed.
    fireEvent.click(screen.getByText('Delete all'));
    expect(deleteAllNotificationsMock).not.toHaveBeenCalled();

    const confirmButtons = await screen.findAllByRole('button', { name: 'Delete all' });
    const confirmButton = confirmButtons.at(-1);
    if (!confirmButton) throw new Error('confirm button not found');
    fireEvent.click(confirmButton);

    await waitFor(() => expect(deleteAllNotificationsMock).toHaveBeenCalledTimes(1));
    // The list is refetched off the invalidated key rather than trusting the mutation result.
    await waitFor(() => expect(listNotificationsMock.mock.calls.length).toBeGreaterThan(1));
  });

  it.each([
    [
      'DEPLOYMENT_SUBMITTED' as const,
      { datasource: 'payments-pipeline', submitter: 'dev@acme.com', submitter_name: 'Dev',
        environment: 'production', version: '2.4.1' },
      /Dev submitted a deployment of 2\.4\.1 to production on payments-pipeline/,
      '/reviews?tab=deployments',
    ],
    [
      'DEPLOYMENT_APPROVED' as const,
      { datasource: 'payments-pipeline', environment: 'production', version: '2.4.1' },
      /deployment of 2\.4\.1 to production was approved/,
      '/deployments/dr-1',
    ],
    [
      'DEPLOYMENT_REJECTED' as const,
      { datasource: 'payments-pipeline', environment: 'production', version: '2.4.1' },
      /deployment of 2\.4\.1 to production was rejected/,
      '/deployments/dr-1',
    ],
    [
      'DEPLOYMENT_OUTCOME_FAILED' as const,
      { datasource: 'payments-pipeline', outcome: 'ROLLED_BACK' as const },
      /deployment you approved on payments-pipeline reported rolled back/,
      '/reviews?tab=rollbacks',
    ],
    [
      'DEPLOYMENT_BREAK_GLASS_EXECUTED' as const,
      { datasource: 'payments-pipeline', submitter: 'dev@acme.com' },
      /dev@acme.com released a break-glass deployment on payments-pipeline/,
      '/deployments/dr-1',
    ],
  ])('renders %s and routes into the deploygov UI (#696)', async (
    eventType,
    payload,
    expected,
    expectedRoute,
  ) => {
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    markNotificationReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'dep1',
          event_type: eventType,
          query_request_id: null,
          api_request_id: null,
          deployment_request_id: 'dr-1',
          payload,
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    const text = await screen.findByText(expected);
    const row = text.closest('.ant-list-item');
    if (!row) throw new Error('list row not found');
    fireEvent.click(row);

    await waitFor(() => expect(markNotificationReadMock).toHaveBeenCalledWith('dep1'));
    expect(navigateMock).toHaveBeenCalledWith(expectedRoute);
  });

  it('routes a plain FAILED outcome to the deployment, not the rollback worklist', async () => {
    // A rollback review only exists for ROLLED_BACK; a FAILED deploy would never appear there.
    fetchUnreadCountMock.mockResolvedValue({ count: 1 });
    markNotificationReadMock.mockResolvedValue(undefined);
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'dep-failed',
          event_type: 'DEPLOYMENT_OUTCOME_FAILED',
          query_request_id: null,
          api_request_id: null,
          deployment_request_id: 'dr-9',
          payload: { datasource: 'payments-pipeline', outcome: 'FAILED' },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    const text = await screen.findByText(/reported failed/);
    const row = text.closest('.ant-list-item');
    if (!row) throw new Error('list row not found');
    fireEvent.click(row);

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/deployments/dr-9'));
  });

  it('renders the no-submitter deployment variants', async () => {
    fetchUnreadCountMock.mockResolvedValue({ count: 2 });
    listNotificationsMock.mockResolvedValue(
      page([
        {
          id: 'dep2',
          event_type: 'DEPLOYMENT_SUBMITTED',
          query_request_id: null,
          api_request_id: null,
          deployment_request_id: 'dr-2',
          payload: { datasource: 'payments-pipeline' },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
        {
          id: 'dep3',
          event_type: 'DEPLOYMENT_BREAK_GLASS_EXECUTED',
          query_request_id: null,
          api_request_id: null,
          deployment_request_id: 'dr-3',
          payload: { datasource: 'payments-pipeline' },
          read: false,
          created_at: new Date().toISOString(),
          read_at: null,
        },
      ]),
    );

    render(wrap(<NotificationBell />));
    fireEvent.click(screen.getByLabelText('Notifications'));
    await waitFor(() => {
      expect(
        screen.getByText('New deployment awaiting review on payments-pipeline'),
      ).toBeInTheDocument();
      expect(
        screen.getByText('A break-glass deployment was released on payments-pipeline'),
      ).toBeInTheDocument();
    });
  });
});
