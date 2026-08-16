import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import '@/i18n';
import type { ReviewDelegation } from '@/types/api';
import { ReviewDelegationSection } from './ReviewDelegationSection';

const {
  listMyReviewDelegationsMock,
  listDelegateCandidatesMock,
  createReviewDelegationMock,
  revokeReviewDelegationMock,
  listDatasourcesMock,
} = vi.hoisted(() => ({
  listMyReviewDelegationsMock: vi.fn(),
  listDelegateCandidatesMock: vi.fn(),
  createReviewDelegationMock: vi.fn(),
  revokeReviewDelegationMock: vi.fn(),
  listDatasourcesMock: vi.fn(),
}));

vi.mock('@/api/reviewDelegations', async () => {
  const actual = await vi.importActual<typeof import('@/api/reviewDelegations')>(
    '@/api/reviewDelegations',
  );
  return {
    ...actual,
    listMyReviewDelegations: listMyReviewDelegationsMock,
    listDelegateCandidates: listDelegateCandidatesMock,
    createReviewDelegation: createReviewDelegationMock,
    revokeReviewDelegation: revokeReviewDelegationMock,
  };
});

vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>('@/api/datasources');
  return { ...actual, listDatasources: listDatasourcesMock };
});

const delegation = (overrides: Partial<ReviewDelegation> = {}): ReviewDelegation => ({
  id: 'd1',
  delegator: { id: 'u1', email: 'alice@example.com', display_name: 'Alice' },
  delegate: { id: 'u2', email: 'bob@example.com', display_name: 'Bob' },
  scope_kind: null,
  scope_id: null,
  scope_name: null,
  reason: 'Annual leave',
  starts_at: '2026-08-20T00:00:00Z',
  ends_at: '2026-08-30T00:00:00Z',
  revoked_at: null,
  status: 'ACTIVE',
  created_at: '2026-08-16T09:00:00Z',
  ...overrides,
});

function renderSection() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <App>
        <ReviewDelegationSection />
      </App>
    </QueryClientProvider>,
  );
}

describe('ReviewDelegationSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listDelegateCandidatesMock.mockResolvedValue([
      { id: 'u2', email: 'bob@example.com', display_name: 'Bob' },
    ]);
    listDatasourcesMock.mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      total_elements: 0,
      total_pages: 0,
    });
  });

  it('lists delegations in both directions', async () => {
    listMyReviewDelegationsMock.mockResolvedValue({
      granted: [delegation()],
      received: [delegation({ id: 'd2', status: 'SCHEDULED' })],
    });

    renderSection();

    await waitFor(() => expect(screen.getAllByText('Bob').length).toBeGreaterThan(0));
    expect(screen.getByText('Alice')).toBeInTheDocument();
  });

  it('shows an unscoped delegation as covering every review queue', async () => {
    listMyReviewDelegationsMock.mockResolvedValue({ granted: [delegation()], received: [] });

    renderSection();

    await waitFor(() =>
      expect(screen.getAllByText('All review queues').length).toBeGreaterThan(0),
    );
  });

  it('offers revoke only while a delegation can still confer eligibility', async () => {
    listMyReviewDelegationsMock.mockResolvedValue({
      granted: [delegation({ status: 'EXPIRED' })],
      received: [],
    });

    renderSection();

    await waitFor(() => expect(screen.getByText('Expired')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument();
  });

  it('offers revoke on an active delegation and calls the API', async () => {
    listMyReviewDelegationsMock.mockResolvedValue({ granted: [delegation()], received: [] });
    revokeReviewDelegationMock.mockResolvedValue(undefined);

    renderSection();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Revoke' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Revoke' }));
    fireEvent.click(await screen.findByRole('button', { name: 'OK' }));

    await waitFor(() => expect(revokeReviewDelegationMock).toHaveBeenCalledWith('d1'));
  });

  it('does not submit until a delegate and window are chosen', async () => {
    listMyReviewDelegationsMock.mockResolvedValue({ granted: [], received: [] });

    renderSection();

    await waitFor(() => expect(listMyReviewDelegationsMock).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: /Delegate review duty/i }));

    // Validation parity with the backend: both are @NotNull on CreateReviewDelegationRequest.
    await waitFor(() => expect(screen.getByText('Choose a delegate')).toBeInTheDocument());
    expect(screen.getByText('Choose a start and end time')).toBeInTheDocument();
    expect(createReviewDelegationMock).not.toHaveBeenCalled();
  });

  it('explains the delegation rules up front', async () => {
    listMyReviewDelegationsMock.mockResolvedValue({ granted: [], received: [] });

    renderSection();

    await waitFor(() =>
      expect(screen.getByText(/never act on a request you submitted/i)).toBeInTheDocument(),
    );
  });
});
