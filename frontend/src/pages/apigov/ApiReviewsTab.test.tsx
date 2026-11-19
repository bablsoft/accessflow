import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ApiConnectorPage, PendingApiReview, PendingApiReviewPage } from '@/types/api';

const { listPendingApiReviewsMock, approveApiReviewMock, rejectApiReviewMock, listApiConnectorsMock } =
  vi.hoisted(() => ({
    listPendingApiReviewsMock: vi.fn(),
    approveApiReviewMock: vi.fn(),
    rejectApiReviewMock: vi.fn(),
    listApiConnectorsMock: vi.fn(),
  }));

vi.mock('@/api/apiRequests', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiRequests')>('@/api/apiRequests');
  return {
    ...actual,
    listPendingApiReviews: listPendingApiReviewsMock,
    approveApiReview: approveApiReviewMock,
    rejectApiReview: rejectApiReviewMock,
  };
});

vi.mock('@/api/apiConnectors', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/apiConnectors')>('@/api/apiConnectors');
  return { ...actual, listApiConnectors: listApiConnectorsMock };
});

const { ApiReviewsTab } = await import('./ApiReviewsTab');

const baseRow: PendingApiReview = {
  api_request_id: 'req-1',
  connector_id: 'conn-1',
  connector_name: 'Payments API',
  submitted_by_user_id: 'u-other',
  verb: 'POST',
  request_path: '/v1/refunds',
  write: true,
  justification: 'refund a duplicate charge',
  ai_analysis_id: null,
  ai_risk_level: 'HIGH',
  ai_risk_score: 80,
  ai_summary: null,
  current_stage: 1,
  variable_override_count: 2,
  created_at: '2026-09-01T10:00:00Z',
};

const secondRow: PendingApiReview = {
  ...baseRow,
  api_request_id: 'req-2',
  connector_id: 'conn-2',
  connector_name: 'Inventory API',
  verb: 'GET',
  request_path: '/v1/stock',
  write: false,
  ai_risk_level: 'LOW',
  ai_risk_score: 10,
  variable_override_count: 0,
};

function reviewPage(content: PendingApiReview[], total = content.length): PendingApiReviewPage {
  return { content, page: 0, size: 20, total_elements: total, total_pages: 1 };
}

function connectorPage(): ApiConnectorPage {
  return {
    content: [
      { id: 'conn-1', name: 'Payments API' } as ApiConnectorPage['content'][number],
      { id: 'conn-2', name: 'Inventory API' } as ApiConnectorPage['content'][number],
    ],
    page: 0,
    size: 100,
    total_elements: 2,
    total_pages: 1,
  };
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/reviews?tab=api']}>
        <AntdApp>
          <Routes>
            <Route path="/reviews" element={node} />
            <Route path="/api-requests/:id" element={<LocationProbe />} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('ApiReviewsTab (#772)', () => {
  beforeEach(() => {
    listPendingApiReviewsMock.mockReset();
    approveApiReviewMock.mockReset();
    rejectApiReviewMock.mockReset();
    listApiConnectorsMock.mockReset().mockResolvedValue(connectorPage());
  });

  it('renders the queue rows and the filtered-of-total count', async () => {
    listPendingApiReviewsMock.mockResolvedValue(reviewPage([baseRow, secondRow], 5));
    render(wrap(<ApiReviewsTab />));

    expect(await screen.findByText('/v1/refunds')).toBeInTheDocument();
    expect(screen.getByText('/v1/stock')).toBeInTheDocument();
    expect(screen.getByText('Payments API')).toBeInTheDocument();
    expect(screen.getByText('2 of 5')).toBeInTheDocument();
    // The override badge only renders for rows that override connector variables.
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(listPendingApiReviewsMock).toHaveBeenCalledWith({
      connector_id: undefined,
      verb: undefined,
      page: 0,
      size: 20,
    });
  });

  it('renders the empty state when nothing is waiting', async () => {
    listPendingApiReviewsMock.mockResolvedValue(reviewPage([]));
    render(wrap(<ApiReviewsTab />));
    expect(await screen.findByText('No API requests awaiting review')).toBeInTheDocument();
  });

  it('filters by risk and free text client-side without refetching', async () => {
    listPendingApiReviewsMock.mockResolvedValue(reviewPage([baseRow, secondRow]));
    render(wrap(<ApiReviewsTab />));
    await screen.findByText('/v1/refunds');

    const search = screen.getByPlaceholderText('Search by connector, path');
    await act(async () => {
      fireEvent.change(search, { target: { value: 'stock' } });
    });
    expect(screen.queryByText('/v1/refunds')).not.toBeInTheDocument();
    expect(screen.getByText('/v1/stock')).toBeInTheDocument();
    expect(screen.getByText('1 of 2')).toBeInTheDocument();
    expect(listPendingApiReviewsMock).toHaveBeenCalledTimes(1);
  });

  it('refetches with the connector filter and resets to the first page', async () => {
    listPendingApiReviewsMock.mockResolvedValue(reviewPage([baseRow, secondRow]));
    render(wrap(<ApiReviewsTab />));
    await screen.findByText('/v1/refunds');

    const connectorSelect = screen.getByText('All connectors');
    await act(async () => {
      fireEvent.mouseDown(connectorSelect);
    });
    const option = await screen.findByTitle('Inventory API');
    await act(async () => {
      fireEvent.click(option);
    });

    await waitFor(() => {
      expect(listPendingApiReviewsMock).toHaveBeenLastCalledWith({
        connector_id: 'conn-2',
        verb: undefined,
        page: 0,
        size: 20,
      });
    });
  });

  it('approves a request with the modal comment', async () => {
    listPendingApiReviewsMock.mockResolvedValue(reviewPage([baseRow]));
    approveApiReviewMock.mockResolvedValue({
      decision_id: 'dec-1',
      decision: 'APPROVED',
      resulting_status: 'APPROVED',
      was_idempotent_replay: false,
    });
    render(wrap(<ApiReviewsTab />));

    const approveBtn = await screen.findByRole('button', { name: 'Approve' });
    await act(async () => {
      fireEvent.click(approveBtn);
    });
    const textarea = await screen.findByPlaceholderText('Comment');
    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'looks safe' } });
    });
    await act(async () => {
      const dialog = screen.getByRole('dialog');
      fireEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    });

    await waitFor(() => {
      expect(approveApiReviewMock).toHaveBeenCalledWith('req-1', 'looks safe');
    });
    expect(rejectApiReviewMock).not.toHaveBeenCalled();
    // The decision invalidates the queue, which refetches it.
    await waitFor(() => expect(listPendingApiReviewsMock).toHaveBeenCalledTimes(2));
  });

  it('rejects a request from the row action', async () => {
    listPendingApiReviewsMock.mockResolvedValue(reviewPage([baseRow]));
    rejectApiReviewMock.mockResolvedValue({
      decision_id: 'dec-2',
      decision: 'REJECTED',
      resulting_status: 'REJECTED',
      was_idempotent_replay: false,
    });
    render(wrap(<ApiReviewsTab />));

    const rejectBtn = await screen.findByRole('button', { name: 'Reject' });
    await act(async () => {
      fireEvent.click(rejectBtn);
    });
    await act(async () => {
      const dialog = await screen.findByRole('dialog');
      fireEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    });

    await waitFor(() => {
      expect(rejectApiReviewMock).toHaveBeenCalledWith('req-1', '');
    });
    expect(approveApiReviewMock).not.toHaveBeenCalled();
  });

  it('navigates to the request detail when a row is clicked', async () => {
    listPendingApiReviewsMock.mockResolvedValue(reviewPage([baseRow]));
    render(wrap(<ApiReviewsTab />));

    const cell = await screen.findByText('/v1/refunds');
    await act(async () => {
      fireEvent.click(cell);
    });
    expect(await screen.findByTestId('location')).toHaveTextContent('/api-requests/req-1');
  });
});

describe('ApiReviewsTab — optimistic decisions and preview', () => {
  function stubWide(matches: boolean) {
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
    listPendingApiReviewsMock.mockReset();
    approveApiReviewMock.mockReset();
    rejectApiReviewMock.mockReset();
    listApiConnectorsMock.mockReset().mockResolvedValue(connectorPage());
  });

  async function approveFirstRow() {
    fireEvent.keyDown(window, { key: 'a' });
    const dialog = await screen.findByRole('dialog');
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    });
  }

  it('removes the decided row at once and selects its neighbour', async () => {
    listPendingApiReviewsMock.mockResolvedValue(reviewPage([baseRow, secondRow]));
    approveApiReviewMock.mockReturnValue(new Promise(() => undefined));
    const { container } = render(wrap(<ApiReviewsTab />));
    await screen.findByText('/v1/refunds');

    await approveFirstRow();

    await waitFor(() => expect(screen.queryByText('/v1/refunds')).toBeNull());
    expect(approveApiReviewMock).toHaveBeenCalledWith('req-1', '');
    expect(container.querySelector('.af-triage-row-active')).toHaveTextContent('/v1/stock');
  });

  it('puts the row back when the server refuses', async () => {
    listPendingApiReviewsMock.mockResolvedValue(reviewPage([baseRow, secondRow]));
    approveApiReviewMock.mockRejectedValue(new Error('nope'));
    const { container } = render(wrap(<ApiReviewsTab />));
    await screen.findByText('/v1/refunds');

    await approveFirstRow();

    await waitFor(() => expect(approveApiReviewMock).toHaveBeenCalled());
    expect(await screen.findByText('/v1/refunds')).toBeInTheDocument();
    await waitFor(() =>
      expect(container.querySelector('.af-triage-row-active')).toHaveTextContent('/v1/refunds'),
    );
  });

  it('previews the selected request on a wide screen and decides from the panel', async () => {
    const restore = stubWide(true);
    try {
      listPendingApiReviewsMock.mockResolvedValue(
        reviewPage([{ ...baseRow, ai_summary: 'Moves money' }, secondRow]),
      );
      render(wrap(<ApiReviewsTab />));
      await screen.findByText('Moves money');
      const preview = document.querySelector('.af-preview') as HTMLElement;
      expect(preview).toHaveTextContent('refund a duplicate charge');
      expect(preview).toHaveTextContent('Payments API');

      // A click selects instead of navigating; the preview follows.
      fireEvent.click(screen.getAllByText('/v1/stock')[0]!);
      expect(preview).toHaveTextContent('Inventory API');
      expect(screen.queryByTestId('location')).toBeNull();

      fireEvent.click(preview.querySelector('.ant-btn-dangerous') as HTMLElement);
      expect(await screen.findByRole('dialog')).toHaveTextContent('Reject');
    } finally {
      restore();
    }
  });
});

