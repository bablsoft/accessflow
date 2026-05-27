import type { ReviewPlanTemplate } from '@/types/api';

export interface ReviewPlanFormApproverRow {
  user_id: string | null;
  role: 'ADMIN' | 'REVIEWER' | null;
  stage: number;
}

export interface ReviewPlanFormValues {
  name: string;
  description?: string;
  requires_ai_review: boolean;
  requires_human_approval: boolean;
  min_approvals_required: number;
  approval_timeout_hours: number;
  auto_approve_reads: boolean;
  approvers: ReviewPlanFormApproverRow[];
}

export const REVIEW_PLAN_DEFAULT_VALUES: ReviewPlanFormValues = {
  name: '',
  description: '',
  requires_ai_review: true,
  requires_human_approval: true,
  min_approvals_required: 1,
  approval_timeout_hours: 24,
  auto_approve_reads: false,
  approvers: [{ user_id: null, role: 'REVIEWER', stage: 1 }],
};

export function reviewPlanFormFromTemplate(
  template: ReviewPlanTemplate,
): ReviewPlanFormValues {
  const approvers: ReviewPlanFormApproverRow[] = template.defaults.approvers.length
    ? template.defaults.approvers.map((a) => ({
        user_id: null,
        role: a.role,
        stage: a.stage,
      }))
    : [{ user_id: null, role: 'REVIEWER', stage: 1 }];
  return {
    name: '',
    description: '',
    requires_ai_review: template.defaults.requires_ai_review,
    requires_human_approval: template.defaults.requires_human_approval,
    min_approvals_required: template.defaults.min_approvals_required,
    approval_timeout_hours: template.defaults.approval_timeout_hours,
    auto_approve_reads: template.defaults.auto_approve_reads,
    approvers,
  };
}
