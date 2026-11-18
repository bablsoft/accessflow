import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { PendingReviewsPage } from '@/types/api';

const {
  listPendingReviewsMock,
  approveQueryMock,
  rejectQueryMock,
  bulkDecideReviewsMock,
} = vi.hoisted(() => ({
  listPendingReviewsMock: vi.fn(),
  approveQueryMock: vi.fn(),
  rejectQueryMock: vi.fn(),
  bulkDecideReviewsMock: vi.fn(),
}));

vi.mock('@/api/reviews', () => ({
  listPendingReviews: listPendingReviewsMock,
  approveQuery: approveQueryMock,
  rejectQuery: rejectQueryMock,
  bulkDecideReviews: bulkDecideReviewsMock,
  reviewKeys: {
    all: ['reviews'] as const,
    pending: () => ['reviews', 'pending'] as const,
    pendingFor: (filters: unknown) => ['reviews', 'pending', filters] as const,
  },
}));

const { QueryReviewsTab } = await import('./QueryReviewsTab');

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

describe('QueryReviewsTab — reject modal flow (AF-269)', () => {
  beforeEach(() => {
    listPendingReviewsMock.mockReset();
    approveQueryMock.mockReset();
    rejectQueryMock.mockReset();
    bulkDecideReviewsMock.mockReset();
    useAuthStore.setState({
      user: {
        id: 'u-reviewer',
        email: 'reviewer@example.com',
        display_name: 'Rev',
        role: 'REVIEWER',
        role_id: null,
        permissions: ['QUERY_SUBMIT_SELECT', 'QUERY_REVIEW'],
        auth_provider: 'LOCAL',
        totp_enabled: false,
        platform_admin: false,
        preferred_language: 'en',
      },
      accessToken: 'token',
    });
  });

  it('opens the reject modal when the card Reject button is clicked', async () => {
    listPendingReviewsMock.mockResolvedValue(pendingPage());

    render(wrap(<QueryReviewsTab />));

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

    render(wrap(<QueryReviewsTab />));

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

  it('does not show the bulk action bar until a row is selected', async () => {
    listPendingReviewsMock.mockResolvedValue(pendingPage());

    render(wrap(<QueryReviewsTab />));

    await screen.findByText('submitter@example.com');
    expect(screen.queryByText(/selected/)).toBeNull();
    expect(
      screen.queryByRole('button', { name: /Approve selected/ }),
    ).toBeNull();
  });

  it('shows the bulk action bar and fires bulkDecideReviews on Approve selected', async () => {
    const ids = ['aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'];
    listPendingReviewsMock.mockResolvedValue(pendingPage());
    bulkDecideReviewsMock.mockResolvedValue({
      results: ids.map((id) => ({
        query_request_id: id,
        status: 'SUCCESS' as const,
        decision: 'APPROVED' as const,
        resulting_status: 'APPROVED' as const,
        decision_id: 'd-1',
        idempotent_replay: false,
      })),
    });

    render(wrap(<QueryReviewsTab />));

    // Wait for the table to render. Then click the row's checkbox.
    await screen.findByText('submitter@example.com');
    const checkboxes = screen.getAllByRole('checkbox');
    // First checkbox is the header select-all; the row checkboxes follow.
    await act(async () => {
      fireEvent.click(checkboxes[1]!);
    });

    // Sticky action bar appears with the count.
    expect(await screen.findByText('1 selected')).toBeInTheDocument();
    const approveSelected = screen.getByRole('button', { name: /Approve selected/ });
    await act(async () => {
      fireEvent.click(approveSelected);
    });

    // Bulk decision modal opens — click its confirm.
    expect(await screen.findByText('Approve selected queries')).toBeInTheDocument();
    const confirmButtons = screen.getAllByRole('button', { name: /Approve selected/ });
    // The first instance is the sticky bar button; the modal's primary button is the last.
    const confirm = confirmButtons[confirmButtons.length - 1]!;
    await act(async () => {
      fireEvent.click(confirm);
    });

    await waitFor(() => {
      expect(bulkDecideReviewsMock).toHaveBeenCalledWith({
        queryIds: ids,
        decision: 'APPROVED',
        comment: undefined,
      });
    });
  });

  it('blocks bulk reject confirm until a comment is typed', async () => {
    listPendingReviewsMock.mockResolvedValue(pendingPage());

    render(wrap(<QueryReviewsTab />));

    await screen.findByText('submitter@example.com');
    const checkboxes = screen.getAllByRole('checkbox');
    await act(async () => {
      fireEvent.click(checkboxes[1]!);
    });

    const rejectSelected = await screen.findByRole('button', {
      name: /Reject selected/,
    });
    await act(async () => {
      fireEvent.click(rejectSelected);
    });

    // Modal opens. The primary confirm (last "Reject selected" button) is disabled
    // until a comment is entered.
    const buttons = screen.getAllByRole('button', { name: /Reject selected/ });
    const modalConfirm = buttons[buttons.length - 1]!;
    expect(modalConfirm).toBeDisabled();

    const textarea = screen.getByPlaceholderText(/Comment that will apply/);
    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'too risky' } });
    });

    await waitFor(() => {
      expect(modalConfirm).not.toBeDisabled();
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

    render(wrap(<QueryReviewsTab />));

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

  it('renders the approval-likelihood badge for a scored row (AF-645)', async () => {
    const page = pendingPage();
    page.content[0]!.approval_probability = 0.78;
    listPendingReviewsMock.mockResolvedValue(page);

    render(wrap(<QueryReviewsTab />));

    expect(await screen.findByTestId('approval-prediction-badge')).toHaveTextContent('78%');
    expect(
      screen.getByRole('columnheader', { name: 'Approval likelihood' }),
    ).toBeInTheDocument();
  });

  it('flags a row whose BLOCK rule findings stopped auto-approval (#865)', async () => {
    const page = pendingPage();
    page.content[0]!.sql_review_blocking_count = 2;
    listPendingReviewsMock.mockResolvedValue(page);

    render(wrap(<QueryReviewsTab />));

    expect(await screen.findByTestId('sql-review-blocking')).toHaveTextContent('2 block');
  });

  it('shows no blocking tag when no rule blocked the row (#865)', async () => {
    listPendingReviewsMock.mockResolvedValue(pendingPage());

    render(wrap(<QueryReviewsTab />));

    await screen.findByRole('columnheader', { name: 'Approval likelihood' });
    expect(screen.queryByTestId('sql-review-blocking')).toBeNull();
  });

  it('renders a dash in the approval-likelihood column for an unscored row (AF-645)', async () => {
    listPendingReviewsMock.mockResolvedValue(pendingPage());

    render(wrap(<QueryReviewsTab />));

    await screen.findByRole('columnheader', { name: 'Approval likelihood' });
    expect(await screen.findByTestId('approval-likelihood-empty')).toHaveTextContent('—');
    expect(screen.queryByTestId('approval-prediction-badge')).toBeNull();
  });

  it('tags a row the caller can only review through a delegation (#622)', async () => {
    const page = pendingPage();
    page.content[0]!.delegated_for = { id: 'delegator-1' };
    listPendingReviewsMock.mockResolvedValue(page);

    render(wrap(<QueryReviewsTab />));

    expect(await screen.findByTestId('delegated-tag')).toHaveTextContent('Delegated');
  });

  it('leaves the delegated column empty when the caller is eligible in their own right', async () => {
    listPendingReviewsMock.mockResolvedValue(pendingPage());

    render(wrap(<QueryReviewsTab />));

    await screen.findByRole('columnheader', { name: 'Delegated' });
    expect(screen.queryByTestId('delegated-tag')).toBeNull();
  });
});

describe('QueryReviewsTab — triage', () => {
  const second = 'bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee';

  function twoRows(): PendingReviewsPage {
    const page = pendingPage();
    const first = page.content[0]!;
    return {
      ...page,
      total_elements: 2,
      content: [
        first,
        { ...first, id: second, sql_text: 'DELETE FROM orders', justification: 'cleanup' },
      ],
    };
  }

  function stubSplit(matches: boolean) {
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({
      matches,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia;
    return () => {
      window.matchMedia = original;
    };
  }

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
        role_id: null,
        permissions: ['QUERY_SUBMIT_SELECT', 'QUERY_REVIEW'],
        auth_provider: 'LOCAL',
        totp_enabled: false,
        platform_admin: false,
        preferred_language: 'en',
      },
      accessToken: 'token',
    });
  });

  it('removes an approved row at once and keeps it gone on success', async () => {
    listPendingReviewsMock.mockResolvedValue(twoRows());
    let resolve: () => void = () => undefined;
    approveQueryMock.mockReturnValue(new Promise<void>((r) => { resolve = r; }));
    render(wrap(<QueryReviewsTab />));
    await screen.findByText('aaaaaaaa');

    fireEvent.keyDown(window, { key: 'a' });

    await waitFor(() => expect(screen.queryByText('aaaaaaaa')).toBeNull());
    expect(approveQueryMock.mock.calls[0]![0]).toBe('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee');
    expect(screen.getByText('bbbbbbbb')).toBeInTheDocument();
    listPendingReviewsMock.mockResolvedValue({ ...twoRows(), content: [twoRows().content[1]!] });
    await act(async () => resolve());
    expect(await screen.findByText('Approved · forwarded to execution')).toBeInTheDocument();
  });

  it('restores the row when the server refuses the decision', async () => {
    listPendingReviewsMock.mockResolvedValue(twoRows());
    approveQueryMock.mockRejectedValue(new Error('nope'));
    render(wrap(<QueryReviewsTab />));
    await screen.findByText('aaaaaaaa');

    fireEvent.keyDown(window, { key: 'A' });

    await waitFor(() => expect(approveQueryMock).toHaveBeenCalled());
    expect(await screen.findByText('aaaaaaaa')).toBeInTheDocument();
  });

  it('moves the selection with J/K and rejects the selected row with R', async () => {
    listPendingReviewsMock.mockResolvedValue(twoRows());
    rejectQueryMock.mockResolvedValue(undefined);
    const { container } = render(wrap(<QueryReviewsTab />));
    await screen.findByText('aaaaaaaa');
    const activeRow = () => container.querySelector('.af-triage-row-active');
    expect(activeRow()).toHaveTextContent('aaaaaaaa');

    fireEvent.keyDown(window, { key: 'j' });
    expect(activeRow()).toHaveTextContent('bbbbbbbb');
    fireEvent.keyDown(window, { key: 'k' });
    expect(activeRow()).toHaveTextContent('aaaaaaaa');
    fireEvent.keyDown(window, { key: 'j' });

    fireEvent.keyDown(window, { key: 'r' });
    const textarea = await screen.findByPlaceholderText(/Explain why/);
    fireEvent.change(textarea, { target: { value: 'too risky' } });
    fireEvent.click(document.querySelector('.ant-modal-footer .ant-btn-dangerous') as HTMLElement);

    await waitFor(() => expect(rejectQueryMock).toHaveBeenCalledWith(second, 'too risky'));
    expect(await screen.findByText('Rejected · submitter notified')).toBeInTheDocument();
    expect(document.querySelector('.ant-message-success')).toBeInTheDocument();
  });

  it('lists the shortcuts from the ? key', async () => {
    listPendingReviewsMock.mockResolvedValue(twoRows());
    render(wrap(<QueryReviewsTab />));
    await screen.findByText('aaaaaaaa');
    fireEvent.keyDown(window, { key: '?' });
    expect(await screen.findByText('Next request')).toBeInTheDocument();
  });

  it('shows the selected request in the side preview on a wide screen', async () => {
    const restore = stubSplit(true);
    try {
      listPendingReviewsMock.mockResolvedValue(twoRows());
      approveQueryMock.mockResolvedValue(undefined);
      render(wrap(<QueryReviewsTab />));
      // Role queries crawl in a tree this deep under jsdom; the panel is addressed by its class.
      await screen.findByText('aaaaaaaa', { selector: '.af-preview-id' });
      const preview = document.querySelector('.af-preview') as HTMLElement;
      expect(preview).toHaveAttribute('aria-label', 'Request preview');
      expect(preview).toHaveTextContent('why');

      fireEvent.click(screen.getByText('bbbbbbbb'));
      expect(preview).toHaveTextContent('cleanup');
      expect(screen.getByText(/Open full details/).closest('a'))
        .toHaveAttribute('href', `/queries/${second}`);

      fireEvent.click(preview.querySelector('.ant-btn-primary') as HTMLElement);
      await waitFor(() => expect(approveQueryMock.mock.calls[0]![0]).toBe(second));
    } finally {
      restore();
    }
  });

  it('no longer offers the Recently decided filter', async () => {
    listPendingReviewsMock.mockResolvedValue(twoRows());
    render(wrap(<QueryReviewsTab />));
    await screen.findByText('aaaaaaaa');
    expect(screen.queryByText('Recently decided')).toBeNull();
  });
});
