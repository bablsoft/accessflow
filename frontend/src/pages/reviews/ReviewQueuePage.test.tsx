import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { PendingReviewsPage } from '@/types/api';

const { listPendingReviewsMock, approveQueryMock, rejectQueryMock } = vi.hoisted(
  () => ({
    listPendingReviewsMock: vi.fn(),
    approveQueryMock: vi.fn(),
    rejectQueryMock: vi.fn(),
  }),
);

vi.mock('@/api/reviews', () => ({
  listPendingReviews: listPendingReviewsMock,
  approveQuery: approveQueryMock,
  rejectQuery: rejectQueryMock,
  reviewKeys: {
    all: ['reviews'] as const,
    pending: () => ['reviews', 'pending'] as const,
    pendingFor: (filters: unknown) => ['reviews', 'pending', filters] as const,
  },
}));

const { ReviewQueuePage } = await import('./ReviewQueuePage');

function pendingPage(): PendingReviewsPage {
  return {
    content: [
      {
        id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        datasource: { id: 'ds-1', name: 'Prod PG' },
        submitted_by: {
          id: 'u-submitter',
          email: 'submitter@example.com',
        },
        sql_text: 'SELECT 1',
        query_type: 'SELECT',
        justification: 'why',
        ai_analysis: null,
        current_stage: 1,
        created_at: '2026-05-01T10:00:00Z',
      },
    ],
    page: 0,
    size: 50,
    total_elements: 1,
    total_pages: 1,
  };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('ReviewQueuePage — reject modal flow (AF-269)', () => {
  beforeEach(() => {
    listPendingReviewsMock.mockReset();
    approveQueryMock.mockReset();
    rejectQueryMock.mockReset();
    useAuthStore.setState({
      user: {
        id: 'u-reviewer',
        email: 'reviewer@example.com',
        display_name: 'Rev',
        role: 'REVIEWER',
        auth_provider: 'LOCAL',
        totp_enabled: false,
        preferred_language: 'en',
      },
      accessToken: 'token',
    });
  });

  it('opens the reject modal when the card Reject button is clicked', async () => {
    listPendingReviewsMock.mockResolvedValue(pendingPage());

    render(wrap(<ReviewQueuePage />));

    // Find the card's danger Reject button. There are two: the one inside the
    // card, and one in the modal once open. Pick the first by getAllByRole.
    const rejectButtons = await screen.findAllByRole('button', {
      name: /Reject/,
    });
    await act(async () => {
      fireEvent.click(rejectButtons[0]!);
    });

    // Modal title appears in DOM via portal.
    expect(await screen.findByText('Reject query')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/Explain why/)).toBeInTheDocument();
  });

  it('fires rejectQuery with the typed comment when the modal confirm is clicked', async () => {
    listPendingReviewsMock.mockResolvedValue(pendingPage());
    rejectQueryMock.mockResolvedValue({
      query_request_id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      decision_id: 'd-1',
      decision: 'REJECTED',
      resulting_status: 'REJECTED',
      idempotent_replay: false,
    });

    render(wrap(<ReviewQueuePage />));

    const cardRejectButtons = await screen.findAllByRole('button', {
      name: /Reject/,
    });
    await act(async () => {
      fireEvent.click(cardRejectButtons[0]!);
    });

    const textarea = await screen.findByPlaceholderText(/Explain why/);
    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'too risky' } });
    });

    // Now find the confirm button in the modal (it has the same accessible
    // name "Reject" — the icon-less Modal okButton is the last one).
    const modalRejectButtons = screen.getAllByRole('button', { name: /Reject/ });
    const confirm = modalRejectButtons[modalRejectButtons.length - 1]!;
    await act(async () => {
      fireEvent.click(confirm);
    });

    await waitFor(() => {
      expect(rejectQueryMock).toHaveBeenCalledWith(
        'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        'too risky',
      );
    });
  });

  it('approve button on the card still fires approveQuery without opening a modal', async () => {
    listPendingReviewsMock.mockResolvedValue(pendingPage());
    approveQueryMock.mockResolvedValue({
      query_request_id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      decision_id: 'd-1',
      decision: 'APPROVED',
      resulting_status: 'APPROVED',
      idempotent_replay: false,
    });

    render(wrap(<ReviewQueuePage />));

    const approveBtn = await screen.findByRole('button', { name: /Approve/ });
    await act(async () => {
      fireEvent.click(approveBtn);
    });

    await waitFor(() => {
      expect(approveQueryMock).toHaveBeenCalledWith(
        'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      );
    });
    // No reject modal should be visible.
    expect(screen.queryByText('Reject query')).toBeNull();
  });
});
