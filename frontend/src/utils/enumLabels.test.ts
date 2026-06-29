import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import {
  ANOMALY_STATUSES,
  BREAK_GLASS_STATUSES,
  OPTIMIZATION_TYPES,
  anomalyStatusLabel,
  breakGlassStatusLabel,
  commentStatusLabel,
  optimizationTypeLabel,
  queryTemplateChangeLabel,
  reviewDecisionTypeLabel,
  submissionReasonLabel,
} from './enumLabels';

const t = ((key: string) => key) as unknown as TFunction;

describe('commentStatusLabel', () => {
  it('maps each comment status to its enum translation key', () => {
    expect(commentStatusLabel(t, 'OPEN')).toBe('enums.comment_status.OPEN');
    expect(commentStatusLabel(t, 'RESOLVED')).toBe('enums.comment_status.RESOLVED');
  });
});

describe('queryTemplateChangeLabel', () => {
  it('maps each change type to its enum translation key', () => {
    expect(queryTemplateChangeLabel(t, 'CREATED')).toBe('enums.query_template_change_type.CREATED');
    expect(queryTemplateChangeLabel(t, 'UPDATED')).toBe('enums.query_template_change_type.UPDATED');
    expect(queryTemplateChangeLabel(t, 'RESTORED')).toBe('enums.query_template_change_type.RESTORED');
  });
});

describe('optimizationTypeLabel', () => {
  it('maps each optimization type to its enum translation key', () => {
    expect(OPTIMIZATION_TYPES).toEqual(['INDEX', 'REWRITE']);
    expect(optimizationTypeLabel(t, 'INDEX')).toBe('enums.optimization_type.INDEX');
    expect(optimizationTypeLabel(t, 'REWRITE')).toBe('enums.optimization_type.REWRITE');
  });
});

describe('submissionReasonLabel', () => {
  it('maps each submission reason to its enum translation key', () => {
    expect(submissionReasonLabel(t, 'USER_SUBMITTED')).toBe('enums.submission_reason.USER_SUBMITTED');
    expect(submissionReasonLabel(t, 'AI_SUGGESTION')).toBe('enums.submission_reason.AI_SUGGESTION');
    expect(submissionReasonLabel(t, 'EMERGENCY_ACCESS')).toBe(
      'enums.submission_reason.EMERGENCY_ACCESS',
    );
  });
});

describe('reviewDecisionTypeLabel', () => {
  it('maps each review decision to its enum translation key', () => {
    expect(reviewDecisionTypeLabel(t, 'APPROVED')).toBe('enums.decision_type.APPROVED');
    expect(reviewDecisionTypeLabel(t, 'REJECTED')).toBe('enums.decision_type.REJECTED');
    expect(reviewDecisionTypeLabel(t, 'REQUESTED_CHANGES')).toBe(
      'enums.decision_type.REQUESTED_CHANGES',
    );
  });
});

describe('breakGlassStatusLabel', () => {
  it('exposes every break-glass status', () => {
    expect(BREAK_GLASS_STATUSES).toEqual(['PENDING_REVIEW', 'REVIEWED']);
  });

  it('maps each status to its enum translation key', () => {
    expect(breakGlassStatusLabel(t, 'PENDING_REVIEW')).toBe(
      'enums.break_glass_status.PENDING_REVIEW',
    );
    expect(breakGlassStatusLabel(t, 'REVIEWED')).toBe('enums.break_glass_status.REVIEWED');
  });
});

describe('anomalyStatusLabel', () => {
  it('exposes every behaviour-anomaly status', () => {
    expect(ANOMALY_STATUSES).toEqual(['OPEN', 'ACKNOWLEDGED', 'DISMISSED']);
  });

  it('maps each status to its enum translation key', () => {
    expect(anomalyStatusLabel(t, 'OPEN')).toBe('enums.behavior_anomaly_status.OPEN');
    expect(anomalyStatusLabel(t, 'ACKNOWLEDGED')).toBe('enums.behavior_anomaly_status.ACKNOWLEDGED');
    expect(anomalyStatusLabel(t, 'DISMISSED')).toBe('enums.behavior_anomaly_status.DISMISSED');
  });
});
