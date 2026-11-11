import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import {
  buildDeploymentTimelineStages,
  verificationDetailText,
} from './buildDeploymentTimelineStages';
import type { DeploymentDecision, DeploymentRequest, QueryStatus } from '@/types/api';

// Echoes the key plus its interpolation, so tests assert the shape without pinning English copy.
const t = ((key: string, options?: Record<string, unknown>) =>
  options
    ? `${key}:${Object.entries(options)
        .map(([k, v]) => `${k}=${String(v)}`)
        .join(',')}`
    : key) as unknown as TFunction;

function decision(id: string, d: DeploymentDecision['decision'], comment: string | null = null) {
  return {
    id,
    reviewer_id: `u-${id}`,
    decision: d,
    comment,
    stage: 1,
    decided_at: '2026-08-20T11:00:00Z',
  };
}

function makeRequest(overrides: Partial<DeploymentRequest>): DeploymentRequest {
  return {
    id: 'd-1',
    pipeline_id: 'p-1',
    pipeline_name: 'payments-api',
    provider: 'GITHUB_ACTIONS',
    environment_id: 'env-1',
    environment_name: 'production',
    submitted_by: 'u-1',
    submitted_by_email: 'ci@example.com',
    version: '2.4.1',
    commit_sha: 'abc123',
    artifact_ref: null,
    run_url: null,
    external_run_id: 'run-1',
    metadata: {},
    status: 'PENDING_AI' as QueryStatus,
    submission_reason: 'USER_SUBMITTED',
    justification: null,
    ai_analysis_id: null,
    ai_risk_level: null,
    ai_risk_score: null,
    ai_summary: null,
    required_approvals: 2,
    scheduled_for: null,
    outcome: null,
    outcome_reported_at: null,
    outcome_detail: null,
    verification_status: 'PENDING',
    created_at: '2026-08-20T10:00:00Z',
    decisions: [],
    can_review: false,
    ...overrides,
  };
}

describe('buildDeploymentTimelineStages', () => {
  it('marks the AI stage active while PENDING_AI', () => {
    const stages = buildDeploymentTimelineStages(makeRequest({ status: 'PENDING_AI' }), t);
    expect(stages[0]?.label).toBe('deploygov.detail.timeline_submitted');
    expect(stages[0]?.who).toBe('ci@example.com');
    expect(stages[1]?.active).toBe(true);
    expect(stages[1]?.who).toBe('deploygov.detail.timeline_ai_analyzing');
  });

  it('shows the AI result with risk level when analysis completed', () => {
    const stages = buildDeploymentTimelineStages(
      makeRequest({ status: 'PENDING_REVIEW', ai_risk_level: 'HIGH', ai_risk_score: 71 }),
      t,
    );
    expect(stages[1]?.done).toBe(true);
    expect(stages[1]?.who).toBe('enums.risk_level.HIGH · 71');
    expect(stages[1]?.riskLevel).toBe('HIGH');
    expect(stages[2]?.active).toBe(true);
    expect(stages[2]?.who).toBe('deploygov.detail.timeline_awaiting_reviewer');
  });

  it('marks AI skipped when no analysis exists past PENDING_AI', () => {
    const stages = buildDeploymentTimelineStages(makeRequest({ status: 'PENDING_REVIEW' }), t);
    expect(stages[1]?.skipped).toBe(true);
  });

  it('stops after a rejected review and carries the reject comment', () => {
    const stages = buildDeploymentTimelineStages(
      makeRequest({
        status: 'REJECTED',
        decisions: [decision('1', 'REJECTED', 'too risky')],
      }),
      t,
    );
    const review = stages[stages.length - 1];
    expect(review?.label).toBe('deploygov.detail.timeline_rejected');
    expect(review?.rejected).toBe(true);
    expect(review?.detail).toBe('"too risky"');
  });

  it('labels a timeout distinctly', () => {
    const stages = buildDeploymentTimelineStages(makeRequest({ status: 'TIMED_OUT' }), t);
    const review = stages[stages.length - 1];
    expect(review?.label).toBe('deploygov.detail.timeline_timed_out');
    expect(review?.who).toBe('deploygov.detail.timeline_timed_out_who');
  });

  it('counts approvals on an approved request', () => {
    const stages = buildDeploymentTimelineStages(
      makeRequest({
        status: 'APPROVED',
        decisions: [decision('1', 'APPROVED'), decision('2', 'APPROVED')],
      }),
      t,
    );
    const review = stages.find((s) => s.label === 'deploygov.detail.timeline_review');
    expect(review?.done).toBe(true);
    expect(review?.who).toBe('deploygov.detail.timeline_approvals_who:granted=2,required=2');
    const release = stages[stages.length - 1];
    expect(release?.label).toBe('deploygov.detail.timeline_released');
    expect(release?.active).toBe(true);
  });

  it('marks a break-glass approval without decisions', () => {
    const stages = buildDeploymentTimelineStages(
      makeRequest({ status: 'APPROVED', submission_reason: 'EMERGENCY_ACCESS' }),
      t,
    );
    const review = stages.find((s) => s.label === 'deploygov.detail.timeline_review');
    expect(review?.who).toBe('deploygov.detail.timeline_break_glass');
  });

  it('marks an auto-approval without decisions', () => {
    const stages = buildDeploymentTimelineStages(makeRequest({ status: 'APPROVED' }), t);
    const review = stages.find((s) => s.label === 'deploygov.detail.timeline_review');
    expect(review?.who).toBe('deploygov.detail.timeline_auto_approved');
  });

  it('adds a scheduled stage when scheduled_for is set', () => {
    const stages = buildDeploymentTimelineStages(
      makeRequest({ status: 'APPROVED', scheduled_for: '2026-09-01T00:00:00Z' }),
      t,
    );
    const scheduled = stages.find((s) => s.label === 'deploygov.detail.timeline_scheduled');
    expect(scheduled?.active).toBe(true);
    const release = stages[stages.length - 1];
    // The release stage is not active while the deferral holds.
    expect(release?.active).toBe(false);
  });

  it('shows execution failure when FAILED without an outcome', () => {
    const stages = buildDeploymentTimelineStages(makeRequest({ status: 'FAILED' }), t);
    const release = stages[stages.length - 1];
    expect(release?.label).toBe('deploygov.detail.timeline_execution_failed');
    expect(release?.failed).toBe(true);
  });

  it('appends a failed outcome stage after the EXECUTED → FAILED flip', () => {
    const stages = buildDeploymentTimelineStages(
      makeRequest({
        status: 'FAILED',
        outcome: 'FAILED',
        outcome_reported_at: '2026-08-20T12:00:00Z',
        outcome_detail: 'smoke tests failed',
      }),
      t,
    );
    const release = stages[stages.length - 2];
    expect(release?.label).toBe('deploygov.detail.timeline_released');
    expect(release?.done).toBe(true);
    const outcome = stages[stages.length - 1];
    expect(outcome?.label).toBe('deploygov.detail.timeline_outcome');
    expect(outcome?.who).toBe('enums.deployment_outcome.FAILED');
    expect(outcome?.failed).toBe(true);
    expect(outcome?.detail).toBe('smoke tests failed');
  });

  it('marks a succeeded outcome done', () => {
    const stages = buildDeploymentTimelineStages(
      makeRequest({
        status: 'EXECUTED',
        outcome: 'SUCCEEDED',
        outcome_reported_at: '2026-08-20T12:00:00Z',
      }),
      t,
    );
    const outcome = stages[stages.length - 1];
    expect(outcome?.done).toBe(true);
    expect(outcome?.failed).toBe(false);
  });

  it('marks a cancelled request', () => {
    const stages = buildDeploymentTimelineStages(makeRequest({ status: 'CANCELLED' }), t);
    const release = stages[stages.length - 1];
    expect(release?.label).toBe('deploygov.detail.timeline_cancelled');
    expect(release?.cancelled).toBe(true);
  });

  describe('post-deployment verification stage (#1027)', () => {
    const released = {
      status: 'EXECUTED' as QueryStatus,
      outcome: 'SUCCEEDED' as const,
      outcome_reported_at: '2026-08-20T12:00:00Z',
    };

    it('adds no stage while verification is PENDING', () => {
      const stages = buildDeploymentTimelineStages(makeRequest(released), t);
      expect(stages.map((s) => s.label)).not.toContain('deploygov.detail.timeline_verification');
    });

    it.each([
      ['RUNNING', { active: true }],
      ['PASSED', { done: true }],
      ['FAILED', { failed: true }],
      ['SKIPPED', { skipped: true }],
    ] as const)('renders %s after the outcome stage', (status, flags) => {
      const stages = buildDeploymentTimelineStages(
        makeRequest({
          ...released,
          ...(status === 'FAILED' ? { status: 'FAILED' as QueryStatus } : {}),
          verification_status: status,
          verification_finished_at: status === 'RUNNING' ? null : '2026-08-20T12:15:00Z',
          verification_detail: 'health: 1/1 failed samples',
        }),
        t,
      );
      const last = stages[stages.length - 1];
      expect(stages[stages.length - 2]?.label).toBe('deploygov.detail.timeline_outcome');
      expect(last?.label).toBe('deploygov.detail.timeline_verification');
      expect(last?.who).toBe(`enums.verification_status.${status}`);
      expect(last?.detail).toBe('health: 1/1 failed samples');
      expect(last?.time).toBe(status === 'RUNNING' ? null : '2026-08-20T12:15:00Z');
      expect(last).toMatchObject(flags);
    });

    it('keeps the release stage released when verification failed a SUCCEEDED deploy', () => {
      const stages = buildDeploymentTimelineStages(
        makeRequest({
          ...released,
          status: 'FAILED',
          verification_status: 'FAILED',
        }),
        t,
      );
      const release = stages.find((s) => s.label === 'deploygov.detail.timeline_released');
      expect(release).toMatchObject({ done: true, failed: false });
      expect(stages.map((s) => s.label)).not.toContain('deploygov.detail.timeline_execution_failed');
      const outcome = stages.find((s) => s.label === 'deploygov.detail.timeline_outcome');
      expect(outcome).toMatchObject({ done: true, failed: false });
      expect(stages[stages.length - 1]).toMatchObject({ failed: true });
    });

    it('shows a SKIPPED stage for a request whose first outcome failed', () => {
      const stages = buildDeploymentTimelineStages(
        makeRequest({
          status: 'FAILED',
          outcome: 'FAILED',
          outcome_reported_at: '2026-08-20T12:00:00Z',
          verification_status: 'SKIPPED',
          verification_detail: 'outcome:FAILED',
        }),
        t,
      );
      expect(stages[stages.length - 1]).toMatchObject({
        label: 'deploygov.detail.timeline_verification',
        skipped: true,
        detail: 'deploygov.detail.verificationSkippedOutcome:outcome=enums.deployment_outcome.FAILED',
      });
    });

    it('translates the SKIPPED machine tokens and passes a breach detail through', () => {
      expect(verificationDetailText(t, 'SKIPPED', 'no verification configured')).toBe(
        'deploygov.detail.verificationSkippedNoConfig',
      );
      expect(verificationDetailText(t, 'SKIPPED', 'outcome:ROLLED_BACK')).toBe(
        'deploygov.detail.verificationSkippedOutcome:outcome=enums.deployment_outcome.ROLLED_BACK',
      );
      expect(verificationDetailText(t, 'SKIPPED', 'outcome:SUCCEEDED')).toBe(
        'deploygov.detail.verificationSkippedOutcome:outcome=enums.deployment_outcome.SUCCEEDED',
      );
      expect(verificationDetailText(t, 'SKIPPED', 'outcome:WHATEVER')).toBe('outcome:WHATEVER');
      expect(verificationDetailText(t, 'SKIPPED', 'something new')).toBe('something new');
      expect(verificationDetailText(t, 'FAILED', 'outcome:FAILED')).toBe('outcome:FAILED');
      expect(verificationDetailText(t, 'FAILED', 'health: 1/1 failed samples')).toBe(
        'health: 1/1 failed samples',
      );
      expect(verificationDetailText(t, 'SKIPPED', null)).toBeNull();
      expect(verificationDetailText(t, 'SKIPPED', '')).toBeNull();
    });
  });
});
