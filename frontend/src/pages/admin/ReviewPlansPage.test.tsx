import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ReviewPlan, ReviewPlanTemplate } from '@/types/api';

const {
  listReviewPlansMock,
  listReviewPlanTemplatesMock,
  createReviewPlanMock,
  updateReviewPlanMock,
  deleteReviewPlanMock,
} = vi.hoisted(() => ({
  listReviewPlansMock: vi.fn(),
  listReviewPlanTemplatesMock: vi.fn(),
  createReviewPlanMock: vi.fn(),
  updateReviewPlanMock: vi.fn(),
  deleteReviewPlanMock: vi.fn(),
}));

vi.mock('@/api/reviewPlans', async () => {
  const actual = await vi.importActual<typeof import('@/api/reviewPlans')>(
    '@/api/reviewPlans',
  );
  return {
    ...actual,
    listReviewPlans: listReviewPlansMock,
    listReviewPlanTemplates: listReviewPlanTemplatesMock,
    createReviewPlan: createReviewPlanMock,
    updateReviewPlan: updateReviewPlanMock,
    deleteReviewPlan: deleteReviewPlanMock,
  };
});

const { ReviewPlansPage } = await import('./ReviewPlansPage');

function templateFixtures(): ReviewPlanTemplate[] {
  return [
    {
      key: 'STRICT_WRITES_2_APPROVALS',
      name: 'Strict — writes need 2 approvals',
      description: 'AI required, two reviewers must approve every write.',
      defaults: {
        requires_ai_review: true,
        requires_human_approval: true,
        min_approvals_required: 2,
        approval_timeout_hours: 24,
        auto_approve_reads: false,
        approvers: [
          { role: 'REVIEWER', stage: 1 },
          { role: 'REVIEWER', stage: 2 },
        ],
      },
    },
    {
      key: 'AI_ONLY_NO_HUMAN',
      name: 'AI-only — no human approval',
      description: 'AI analyzes every query; no human reviewer is required.',
      defaults: {
        requires_ai_review: true,
        requires_human_approval: false,
        min_approvals_required: 1,
        approval_timeout_hours: 24,
        auto_approve_reads: false,
        approvers: [],
      },
    },
  ];
}

function noPlans(): ReviewPlan[] {
  return [];
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('ReviewPlansPage — templates', () => {
  beforeEach(() => {
    listReviewPlansMock.mockReset();
    listReviewPlanTemplatesMock.mockReset();
    createReviewPlanMock.mockReset();
    updateReviewPlanMock.mockReset();
    deleteReviewPlanMock.mockReset();
    listReviewPlansMock.mockResolvedValue(noPlans());
  });

  it('fetches templates on mount', async () => {
    listReviewPlanTemplatesMock.mockResolvedValue(templateFixtures());

    render(wrap(<ReviewPlansPage />));

    await waitFor(() => {
      expect(listReviewPlanTemplatesMock).toHaveBeenCalledTimes(1);
    });
  });

  it('renders the primary "Add review plan" button alongside a template dropdown trigger', async () => {
    listReviewPlanTemplatesMock.mockResolvedValue(templateFixtures());

    render(wrap(<ReviewPlansPage />));

    await waitFor(() => {
      expect(listReviewPlanTemplatesMock).toHaveBeenCalled();
    });

    expect(screen.getByRole('button', { name: /Add review plan/ })).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Create from template' }),
    ).toBeInTheDocument();
  });

  it('opens the empty create form when the primary button is clicked', async () => {
    listReviewPlanTemplatesMock.mockResolvedValue(templateFixtures());

    render(wrap(<ReviewPlansPage />));

    await waitFor(() => {
      expect(listReviewPlanTemplatesMock).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByRole('button', { name: /Add review plan/ }));

    expect(
      await screen.findByText('Add review plan', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();
    // DEFAULT_VALUES — min_approvals = 1 when no template was selected.
    const minApprovals = await screen.findByLabelText('Minimum approvals');
    expect(minApprovals).toHaveValue('1');
  });

  it('still renders the page when the templates query fails', async () => {
    listReviewPlanTemplatesMock.mockRejectedValue(new Error('boom'));

    render(wrap(<ReviewPlansPage />));

    await waitFor(() => {
      expect(listReviewPlanTemplatesMock).toHaveBeenCalled();
    });

    // Primary button stays usable even when the templates endpoint fails.
    expect(screen.getByRole('button', { name: /Add review plan/ })).toBeInTheDocument();
  });
});
