import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type {
  DeploymentReviewItem,
  DeploymentReviewPage,
  DeploymentRollbackReview,
  DeploymentRollbackReviewPage,
} from '@/types/api';

const {
  listDeploymentReviewsMock,
  approveDeploymentMock,
  rejectDeploymentMock,
  listDeploymentRollbackReviewsMock,
  acknowledgeDeploymentRollbackMock,
} = vi.hoisted(() => ({
  listDeploymentReviewsMock: vi.fn(),
  approveDeploymentMock: vi.fn(),
  rejectDeploymentMock: vi.fn(),
  listDeploymentRollbackReviewsMock: vi.fn(),
  acknowledgeDeploymentRollbackMock: vi.fn(),
}));

vi.mock('@/api/deploymentReviews', () => ({
  listDeploymentReviews: listDeploymentReviewsMock,
  approveDeployment: approveDeploymentMock,
  rejectDeployment: rejectDeploymentMock,
  listDeploymentRollbackReviews: listDeploymentRollbackReviewsMock,
  acknowledgeDeploymentRollback: acknowledgeDeploymentRollbackMock,
  deploymentReviewKeys: {
    all: ['deployment-reviews'] as const,
    lists: () => ['deployment-reviews', 'list'] as const,
    list: (filters: unknown) => ['deployment-reviews', 'list', filters] as const,
  },
  deploymentRollbackReviewKeys: {
    all: ['deployment-rollback-reviews'] as const,
    lists: () => ['deployment-rollback-reviews', 'list'] as const,
    list: (filters: unknown) => ['deployment-rollback-reviews', 'list', filters] as const,
    detail: (id: string) => ['deployment-rollback-reviews', 'detail', id] as const,
  },
}));

const { PendingDeploymentsTab, RollbackReviewsTab } = await import('./DeploymentReviewTabs');

const baseItem: DeploymentReviewItem = {
  deployment_request_id: 'req-1',
  pipeline_id: 'pipe-1',
  pipeline_name: 'Checkout Service',
  environment_id: 'env-1',
  environment_name: 'production',
  submitted_by_user_id: 'u-other',
  version: '2.4.1',
  commit_sha: 'abc123def456',
  run_url: null,
  justification: 'Ship the checkout fix',
  ai_analysis_id: null,
  ai_risk_level: 'HIGH',
  ai_risk_score: 80,
  ai_summary: null,
  current_stage: 1,
  required_approvals: 2,
  scheduled_for: null,
  created_at: '2026-05-01T10:00:00Z',
};

const baseRollback: DeploymentRollbackReview = {
  id: 'rb-1',
  deployment_request_id: 'req-1',
  pipeline_id: 'pipe-1',
  environment_id: 'env-1',
  submitted_by: 'u-other',
  outcome_detail: 'Rolled back after error-rate spike',
  status: 'PENDING_REVIEW',
  reviewed_by: null,
  review_comment: null,
  reviewed_at: null,
  created_at: '2026-05-01T10:00:00Z',
};

function reviewPage(content: DeploymentReviewItem[]): DeploymentReviewPage {
  return { content, page: 0, size: 20, total_elements: content.length, total_pages: 1 };
}

function rollbackPage(content: DeploymentRollbackReview[]): DeploymentRollbackReviewPage {
  return { content, page: 0, size: 20, total_elements: content.length, total_pages: 1 };
}

function wrap(node: ReactNode, initialEntries: string[] = ['/reviews?tab=deployments']) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={initialEntries}>
        <AntdApp>{node}</AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('DeploymentReviewTabs', () => {
  beforeEach(() => {
    listDeploymentReviewsMock.mockReset();
    approveDeploymentMock.mockReset();
    rejectDeploymentMock.mockReset();
    listDeploymentRollbackReviewsMock.mockReset();
    acknowledgeDeploymentRollbackMock.mockReset();
    listDeploymentRollbackReviewsMock.mockResolvedValue(rollbackPage([]));
    useAuthStore.setState({
      user: {
        id: 'u-reviewer',
        email: 'reviewer@example.com',
        display_name: 'Rev',
        role: 'REVIEWER',
        role_id: null,
        permissions: ['DEPLOYMENT_REVIEW'],
        auth_provider: 'LOCAL',
        totp_enabled: false,
        platform_admin: false,
        preferred_language: 'en',
      },
      accessToken: 'token',
    });
  });

  it('renders pending queue rows', async () => {
    listDeploymentReviewsMock.mockResolvedValue(reviewPage([baseItem]));

    render(wrap(<PendingDeploymentsTab />));

    expect(await screen.findByText('Checkout Service')).toBeInTheDocument();
    expect(screen.getByText('2.4.1')).toBeInTheDocument();
    expect(screen.getByText('production')).toBeInTheDocument();
    expect(screen.getByText('1 of 1')).toBeInTheDocument();
    // The rollback tab is a sibling component, so its list is never fetched here.
    expect(listDeploymentRollbackReviewsMock).not.toHaveBeenCalled();
  });

  it('approves a deployment with the modal comment', async () => {
    listDeploymentReviewsMock.mockResolvedValue(reviewPage([baseItem]));
    approveDeploymentMock.mockResolvedValue({
      decision_id: 'dec-1',
      decision: 'APPROVED',
      resulting_status: 'APPROVED',
      duplicate: false,
    });

    render(wrap(<PendingDeploymentsTab />));

    const approveBtn = await screen.findByRole('button', { name: 'Approve' });
    await act(async () => {
      fireEvent.click(approveBtn);
    });

    const textarea = await screen.findByPlaceholderText('Optional comment for the submitter');
    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'ship it' } });
    });

    await act(async () => {
      const dialog = screen.getByRole('dialog');
      fireEvent.click(within(dialog).getByRole('button', { name: 'Approve' }));
    });

    await waitFor(() => {
      expect(approveDeploymentMock).toHaveBeenCalledWith('req-1', 'ship it');
    });
    expect(rejectDeploymentMock).not.toHaveBeenCalled();
  });

  it("disables approve and reject on the reviewer's own submission", async () => {
    listDeploymentReviewsMock.mockResolvedValue(
      reviewPage([{ ...baseItem, submitted_by_user_id: 'u-reviewer' }]),
    );

    render(wrap(<PendingDeploymentsTab />));

    const approveBtn = await screen.findByRole('button', { name: 'Approve' });
    expect(approveBtn).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled();
  });

  it('acknowledges a rollback with the modal comment', async () => {
    listDeploymentRollbackReviewsMock.mockResolvedValue(rollbackPage([baseRollback]));
    acknowledgeDeploymentRollbackMock.mockResolvedValue({
      ...baseRollback,
      status: 'REVIEWED',
      reviewed_by: 'u-reviewer',
      review_comment: 'root cause: INC-7',
      reviewed_at: '2026-05-02T10:00:00Z',
    });

    render(wrap(<RollbackReviewsTab />, ['/reviews?tab=rollbacks']));

    const ackBtn = await screen.findByRole('button', { name: 'Acknowledge' });
    await act(async () => {
      fireEvent.click(ackBtn);
    });

    expect(await screen.findByText('Acknowledge rollback')).toBeInTheDocument();
    const textarea = screen.getByPlaceholderText('Optional comment (e.g. a root-cause reference)');
    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'root cause: INC-7' } });
    });

    // The modal ok button shares the row button's text — pick the last one.
    const ackButtons = screen.getAllByRole('button', { name: 'Acknowledge' });
    const confirm = ackButtons[ackButtons.length - 1]!;
    await act(async () => {
      fireEvent.click(confirm);
    });

    await waitFor(() => {
      expect(acknowledgeDeploymentRollbackMock).toHaveBeenCalledWith('rb-1', 'root cause: INC-7');
    });
    // The pending tab is a sibling component and was never mounted.
    expect(listDeploymentReviewsMock).not.toHaveBeenCalled();
  });
});
