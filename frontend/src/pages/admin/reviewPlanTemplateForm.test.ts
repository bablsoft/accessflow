import { describe, expect, it } from 'vitest';
import {
  REVIEW_PLAN_DEFAULT_VALUES,
  reviewPlanFormFromTemplate,
} from './reviewPlanTemplateForm';
import type { ReviewPlanTemplate } from '@/types/api';

function template(overrides: Partial<ReviewPlanTemplate['defaults']> = {}): ReviewPlanTemplate {
  return {
    key: 'TEST',
    name: 'Test',
    description: 'A test template',
    defaults: {
      requires_ai_review: true,
      requires_human_approval: true,
      min_approvals_required: 1,
      approval_timeout_hours: 24,
      auto_approve_reads: false,
      approvers: [{ role: 'REVIEWER', stage: 1 }],
      ...overrides,
    },
  };
}

describe('reviewPlanFormFromTemplate', () => {
  it('maps template defaults onto form values with empty name/description', () => {
    const values = reviewPlanFormFromTemplate(
      template({
        requires_ai_review: true,
        requires_human_approval: true,
        min_approvals_required: 2,
        approval_timeout_hours: 48,
        auto_approve_reads: false,
        approvers: [
          { role: 'REVIEWER', stage: 1 },
          { role: 'ADMIN', stage: 2 },
        ],
      }),
    );

    expect(values).toEqual({
      name: '',
      description: '',
      requires_ai_review: true,
      requires_human_approval: true,
      min_approvals_required: 2,
      approval_timeout_hours: 48,
      auto_approve_reads: false,
      approvers: [
        { user_id: null, role: 'REVIEWER', stage: 1 },
        { user_id: null, role: 'ADMIN', stage: 2 },
      ],
    });
  });

  it('seeds a single empty approver row when the template has none', () => {
    const values = reviewPlanFormFromTemplate(template({ approvers: [] }));

    expect(values.approvers).toEqual([{ user_id: null, role: 'REVIEWER', stage: 1 }]);
  });

  it('never copies user_id from a template (only role and stage)', () => {
    const values = reviewPlanFormFromTemplate(
      template({ approvers: [{ role: 'ADMIN', stage: 1 }] }),
    );

    const row = values.approvers[0];
    expect(row).toBeDefined();
    expect(row?.user_id).toBeNull();
    expect(row?.role).toBe('ADMIN');
    expect(row?.stage).toBe(1);
  });

  it('exposes a stable DEFAULT_VALUES record used when no template is selected', () => {
    expect(REVIEW_PLAN_DEFAULT_VALUES.name).toBe('');
    expect(REVIEW_PLAN_DEFAULT_VALUES.requires_ai_review).toBe(true);
    expect(REVIEW_PLAN_DEFAULT_VALUES.requires_human_approval).toBe(true);
    expect(REVIEW_PLAN_DEFAULT_VALUES.min_approvals_required).toBe(1);
    expect(REVIEW_PLAN_DEFAULT_VALUES.approval_timeout_hours).toBe(24);
    expect(REVIEW_PLAN_DEFAULT_VALUES.auto_approve_reads).toBe(false);
    expect(REVIEW_PLAN_DEFAULT_VALUES.approvers).toHaveLength(1);
  });
});
