import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import {
  AUDIT_SINK_TYPES,
  DEPLOYMENT_OUTCOMES,
  DEPLOYMENT_ROLLBACK_REVIEW_STATUSES,
  FREEZE_BEHAVIORS,
  PIPELINE_PROVIDERS,
  deploymentOutcomeLabel,
  deploymentRollbackReviewStatusLabel,
  freezeBehaviorLabel,
  isoWeekdayLabel,
  pipelineProviderLabel,
  auditSinkTypeLabel,
  EXPORT_POLICY_MODES,
  exportPolicyModeLabel,
  GRANT_RESOURCE_KINDS,
  GRANT_USAGE_RECOMMENDATIONS,
  grantResourceKindLabel,
  grantUsageRecommendationLabel,
  ANOMALY_STATUSES,
  API_MASKING_MATCHER_TYPES,
  BREAK_GLASS_STATUSES,
  OPTIMIZATION_TYPES,
  anomalyStatusLabel,
  apiMaskingMatcherTypeLabel,
  breakGlassStatusLabel,
  commentStatusLabel,
  erasureStatusLabel,
  lifecycleActionLabel,
  lifecycleSubjectTypeLabel,
  lifecycleTransformLabel,
  optimizationTypeLabel,
  queryTemplateChangeLabel,
  reviewDecisionTypeLabel,
  roleLabel,
  submissionReasonLabel,
} from './enumLabels';

const t = ((key: string) => key) as unknown as TFunction;

describe('exportPolicyModeLabel', () => {
  it('exposes every export policy mode', () => {
    expect(EXPORT_POLICY_MODES).toEqual(['ALLOW', 'WATERMARK', 'ROW_CAP', 'DENY_CLASSIFIED']);
  });

  it('maps each mode to its enum translation key', () => {
    for (const mode of EXPORT_POLICY_MODES) {
      expect(exportPolicyModeLabel(t, mode)).toBe(`enums.export_policy_mode.${mode}`);
    }
  });
});

describe('auditSinkTypeLabel (#628)', () => {
  it('exposes every audit sink type', () => {
    expect(AUDIT_SINK_TYPES).toEqual([
      'SPLUNK_HEC',
      'SYSLOG_CEF',
      'HTTPS_BATCH',
      'S3_OBJECT_LOCK',
    ]);
  });

  it('maps each sink type to its enum translation key', () => {
    for (const v of AUDIT_SINK_TYPES) {
      expect(auditSinkTypeLabel(t, v)).toBe(`enums.audit_sink_type.${v}`);
    }
  });
});

describe('apiMaskingMatcherTypeLabel', () => {
  it('exposes every matcher type', () => {
    expect(API_MASKING_MATCHER_TYPES).toEqual(['SCHEMA_FIELD', 'JSON_PATH', 'XML_PATH', 'REGEX']);
  });
  it('maps each matcher type to its enum translation key', () => {
    for (const v of API_MASKING_MATCHER_TYPES) {
      expect(apiMaskingMatcherTypeLabel(t, v)).toBe(`enums.api_masking_matcher_type.${v}`);
    }
  });
});

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

describe('lifecycle enum labels', () => {
  it('maps lifecycle action / transform / subject-type / erasure status to enum keys', () => {
    expect(lifecycleActionLabel(t, 'SOFT_DELETE')).toBe('enums.lifecycle_action.SOFT_DELETE');
    expect(lifecycleTransformLabel(t, 'SHA256_SALTED')).toBe(
      'enums.lifecycle_transform.SHA256_SALTED',
    );
    expect(lifecycleSubjectTypeLabel(t, 'EMAIL')).toBe('enums.lifecycle_subject_type.EMAIL');
    expect(erasureStatusLabel(t, 'PENDING_REVIEW')).toBe('enums.erasure_status.PENDING_REVIEW');
  });
});

describe('roleLabel (AF-522)', () => {
  it('localizes system role names via enums.role keys', async () => {
    const i18n = (await import('@/i18n')).default;
    const realT = i18n.t.bind(i18n);
    expect(roleLabel(realT, 'READONLY')).toBe('Read-only');
    expect(roleLabel(realT, 'ADMIN')).toBe('Admin');
  });

  it('falls back to the raw name for custom roles without a translation key', async () => {
    const i18n = (await import('@/i18n')).default;
    const realT = i18n.t.bind(i18n);
    expect(roleLabel(realT, 'Release Manager')).toBe('Release Manager');
  });
});

describe('grant usage labels (#625)', () => {
  it('maps each recommendation and resource kind to its enum translation key', () => {
    for (const recommendation of GRANT_USAGE_RECOMMENDATIONS) {
      expect(grantUsageRecommendationLabel(t, recommendation)).toBe(
        `enums.grant_usage_recommendation.${recommendation}`,
      );
    }
    for (const kind of GRANT_RESOURCE_KINDS) {
      expect(grantResourceKindLabel(t, kind)).toBe(`enums.grant_resource_kind.${kind}`);
    }
  });

  it('lists every recommendation, worst first, and both resource kinds', () => {
    expect(GRANT_USAGE_RECOMMENDATIONS).toEqual([
      'NEVER_USED',
      'STALE',
      'OVER_SCOPED',
      'ACTIVE',
      'INSUFFICIENT_DATA',
    ]);
    expect(GRANT_RESOURCE_KINDS).toEqual(['DATASOURCE', 'API_CONNECTOR']);
  });

  it('resolves every label against the real bundle', async () => {
    const i18n = (await import('@/i18n')).default;
    const realT = i18n.t.bind(i18n);
    for (const recommendation of GRANT_USAGE_RECOMMENDATIONS) {
      expect(grantUsageRecommendationLabel(realT, recommendation)).not.toContain('enums.');
    }
    for (const kind of GRANT_RESOURCE_KINDS) {
      expect(grantResourceKindLabel(realT, kind)).not.toContain('enums.');
    }
  });
});

describe('deployment governance labels (#696)', () => {
  it('lists every provider, behavior, outcome and rollback status', () => {
    expect(PIPELINE_PROVIDERS).toHaveLength(7);
    expect(FREEZE_BEHAVIORS).toEqual(['HOLD', 'REJECT']);
    expect(DEPLOYMENT_OUTCOMES).toEqual(['SUCCEEDED', 'FAILED', 'ROLLED_BACK']);
    expect(DEPLOYMENT_ROLLBACK_REVIEW_STATUSES).toEqual(['PENDING_REVIEW', 'REVIEWED']);
  });

  it('resolves every label against the real bundle', async () => {
    const i18n = (await import('@/i18n')).default;
    const realT = i18n.t.bind(i18n);
    for (const provider of PIPELINE_PROVIDERS) {
      expect(pipelineProviderLabel(realT, provider)).not.toContain('enums.');
    }
    for (const behavior of FREEZE_BEHAVIORS) {
      expect(freezeBehaviorLabel(realT, behavior)).not.toContain('enums.');
    }
    for (const outcome of DEPLOYMENT_OUTCOMES) {
      expect(deploymentOutcomeLabel(realT, outcome)).not.toContain('enums.');
    }
    for (const status of DEPLOYMENT_ROLLBACK_REVIEW_STATUSES) {
      expect(deploymentRollbackReviewStatusLabel(realT, status)).not.toContain('enums.');
    }
  });

  it('maps ISO weekday numbers onto weekday labels, tolerating out-of-range input', async () => {
    const i18n = (await import('@/i18n')).default;
    const realT = i18n.t.bind(i18n);
    expect(isoWeekdayLabel(realT, 1)).toBe('Monday');
    expect(isoWeekdayLabel(realT, 7)).toBe('Sunday');
    expect(isoWeekdayLabel(realT, 0)).toBe('0');
    expect(isoWeekdayLabel(realT, 8)).toBe('8');
  });
});
