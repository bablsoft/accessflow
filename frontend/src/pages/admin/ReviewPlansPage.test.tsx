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
  listApproverRoleServiceAccountsMock,
  listRolesMock,
} = vi.hoisted(() => ({
  listApproverRoleServiceAccountsMock: vi.fn(),
  listRolesMock: vi.fn(),
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
    listApproverRoleServiceAccounts: listApproverRoleServiceAccountsMock,
  };
});

vi.mock('@/api/roles', async () => {
  const actual = await vi.importActual<typeof import('@/api/roles')>('@/api/roles');
  return { ...actual, listRoles: listRolesMock };
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
    listApproverRoleServiceAccountsMock.mockReset();
    listApproverRoleServiceAccountsMock.mockResolvedValue([]);
    listRolesMock.mockReset();
    listRolesMock.mockResolvedValue([]);
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

describe('ReviewPlansPage — service accounts on approver roles', () => {
  beforeEach(() => {
    listReviewPlansMock.mockReset();
    listReviewPlanTemplatesMock.mockReset();
    listApproverRoleServiceAccountsMock.mockReset();
    listRolesMock.mockReset();
    listReviewPlansMock.mockResolvedValue(noPlans());
    listReviewPlanTemplatesMock.mockResolvedValue(templateFixtures());
    listRolesMock.mockResolvedValue([]);
  });

  async function openCreateModal() {
    render(wrap(<ReviewPlansPage />));
    await waitFor(() => {
      expect(listReviewPlansMock).toHaveBeenCalled();
    });
    fireEvent.click(screen.getByRole('button', { name: /Add review plan/ }));
    await screen.findByText('Add review plan', { selector: '.ant-modal-title' });
  }

  it('does not fetch service-account counts until the editor opens', async () => {
    listApproverRoleServiceAccountsMock.mockResolvedValue([]);
    render(wrap(<ReviewPlansPage />));
    await waitFor(() => {
      expect(listReviewPlansMock).toHaveBeenCalled();
    });
    expect(listApproverRoleServiceAccountsMock).not.toHaveBeenCalled();
  });

  it('warns with the count when the default REVIEWER rule includes service accounts', async () => {
    listApproverRoleServiceAccountsMock.mockResolvedValue([
      { role_name: 'reviewer', service_account_count: 2 },
    ]);

    await openCreateModal();

    const warning = await screen.findByTestId('approver-role-service-account-warning');
    expect(warning).toHaveTextContent('2 service accounts hold this role and can approve.');
  });

  it('shows no warning when no service account holds the rule role', async () => {
    listApproverRoleServiceAccountsMock.mockResolvedValue([
      { role_name: 'ANALYST', service_account_count: 3 },
    ]);

    await openCreateModal();

    await waitFor(() => {
      expect(listApproverRoleServiceAccountsMock).toHaveBeenCalled();
    });
    expect(screen.queryByTestId('approver-role-service-account-warning')).toBeNull();
  });

  it('shows no warning when the counts fetch fails', async () => {
    listApproverRoleServiceAccountsMock.mockRejectedValue(new Error('forbidden'));

    await openCreateModal();

    await waitFor(() => {
      expect(listApproverRoleServiceAccountsMock).toHaveBeenCalled();
    });
    expect(screen.queryByTestId('approver-role-service-account-warning')).toBeNull();
  });
});
