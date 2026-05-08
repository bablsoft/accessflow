import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { Datasource, ReviewPlan } from '@/types/api';

const { listReviewPlansMock } = vi.hoisted(() => ({
  listReviewPlansMock: vi.fn(),
}));

vi.mock('@/api/reviewPlans', () => ({
  listReviewPlans: listReviewPlansMock,
  reviewPlanKeys: {
    all: ['reviewPlans'] as const,
    lists: () => ['reviewPlans', 'list'] as const,
    details: () => ['reviewPlans', 'detail'] as const,
    detail: (id: string) => ['reviewPlans', 'detail', id] as const,
  },
}));

import { ReviewPlanPreview } from './ReviewPlanPreview';

const baseDatasource: Datasource = {
  id: 'ds-01',
  organization_id: 'org-1',
  name: 'Production',
  db_type: 'POSTGRESQL',
  host: 'db.internal',
  port: 5432,
  database_name: 'app',
  username: 'svc',
  ssl_mode: 'REQUIRE',
  connection_pool_size: 10,
  max_rows_per_query: 1000,
  require_review_reads: false,
  require_review_writes: true,
  review_plan_id: 'rp-1',
  ai_analysis_enabled: true,
  active: true,
  created_at: '2026-05-01T00:00:00Z',
};

const planFixture: ReviewPlan = {
  id: 'rp-1',
  organization_id: 'org-1',
  name: 'Strict (production)',
  description: 'AI review + 2 human approvers',
  requires_ai_review: true,
  requires_human_approval: true,
  min_approvals_required: 2,
  approval_timeout_hours: 24,
  auto_approve_reads: false,
  notify_channels: [],
  approvers: [],
  created_at: '2026-05-01T00:00:00Z',
};

function withClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>;
}

describe('ReviewPlanPreview', () => {
  beforeEach(() => {
    listReviewPlansMock.mockReset();
  });

  it('renders a skeleton while review plans are loading', () => {
    let resolve: (v: ReviewPlan[]) => void = () => {};
    listReviewPlansMock.mockReturnValueOnce(
      new Promise<ReviewPlan[]>((r) => {
        resolve = r;
      }),
    );

    const { container } = render(withClient(<ReviewPlanPreview ds={baseDatasource} />));

    expect(container.querySelector('.ant-skeleton')).not.toBeNull();
    resolve([]);
  });

  it('renders plan name, timeout, and the AI + human + execute stages once the plan resolves', async () => {
    listReviewPlansMock.mockResolvedValueOnce([planFixture]);

    render(withClient(<ReviewPlanPreview ds={baseDatasource} />));

    await waitFor(() => {
      expect(screen.getByText(/Strict \(production\)/)).toBeInTheDocument();
    });
    expect(screen.getByText('timeout · 24h')).toBeInTheDocument();
    expect(screen.getByText('AI review')).toBeInTheDocument();
    expect(screen.getByText('Human approval · stage 1')).toBeInTheDocument();
    expect(screen.getByText('Human approval · stage 2')).toBeInTheDocument();
    expect(screen.getByText('Execute')).toBeInTheDocument();
  });

  it('renders the empty state when the datasource has no review plan attached', async () => {
    listReviewPlansMock.mockResolvedValueOnce([planFixture]);

    render(
      withClient(
        <ReviewPlanPreview ds={{ ...baseDatasource, review_plan_id: null }} />,
      ),
    );

    expect(await screen.findByText('No review plan attached')).toBeInTheDocument();
    expect(
      screen.getByText(/This datasource has no review plan/),
    ).toBeInTheDocument();
  });

  it('renders the empty state when the datasource references an unknown plan id', async () => {
    listReviewPlansMock.mockResolvedValueOnce([planFixture]);

    render(
      withClient(
        <ReviewPlanPreview ds={{ ...baseDatasource, review_plan_id: 'rp-missing' }} />,
      ),
    );

    expect(await screen.findByText('No review plan attached')).toBeInTheDocument();
  });

  it('omits human stages when the plan does not require human approval', async () => {
    listReviewPlansMock.mockResolvedValueOnce([
      {
        ...planFixture,
        requires_human_approval: false,
        min_approvals_required: 0,
      },
    ]);

    render(withClient(<ReviewPlanPreview ds={baseDatasource} />));

    await waitFor(() => {
      expect(screen.getByText('AI review')).toBeInTheDocument();
    });
    expect(screen.queryByText(/Human approval/)).not.toBeInTheDocument();
    expect(screen.getByText('Execute')).toBeInTheDocument();
  });
});
