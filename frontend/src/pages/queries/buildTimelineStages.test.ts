import { describe, expect, it } from 'vitest';
import { buildTimelineStages } from './buildTimelineStages';
import type {
  QueryDetail,
  QueryStatus,
  ReviewDecisionDetail,
  ReviewDecisionType,
} from '@/types/api';

const t = (key: string) => key;

function reviewer(id: string, displayName: string | null, email: string) {
  return { id, email, display_name: displayName as string };
}

function decision(
  reviewerOverride: ReturnType<typeof reviewer>,
  d: ReviewDecisionType,
  decidedAt = '2026-05-01T10:00:00Z',
): ReviewDecisionDetail {
  return {
    id: `dec-${reviewerOverride.id}-${d}`,
    reviewer: reviewerOverride,
    decision: d,
    comment: null,
    stage: 1,
    decided_at: decidedAt,
  };
}

function makeQuery(overrides: Partial<QueryDetail>): QueryDetail {
  return {
    id: 'q1',
    datasource: { id: 'ds1', name: 'Prod PG' },
    submitted_by: { id: 'u1', email: 'sub@example.com', display_name: 'Sub' },
    sql_text: 'SELECT 1',
    query_type: 'SELECT',
    status: 'PENDING_AI' as QueryStatus,
    justification: '',
    ai_analysis: null,
    rows_affected: null,
    duration_ms: null,
    error_message: null,
    previous_run_id: null,
    review_plan_name: null,
    approval_timeout_hours: null,
    review_decisions: [],
    scheduled_for: null,
    created_at: '2026-05-01T09:00:00Z',
    updated_at: '2026-05-01T09:00:00Z',
    ...overrides,
  };
}

describe('buildTimelineStages — reviewer names on the Human review stage', () => {
  it('shows a single approver display name on an APPROVED query', () => {
    const alice = reviewer('r1', 'Alice', 'alice@example.com');
    const q = makeQuery({
      status: 'APPROVED',
      review_decisions: [decision(alice, 'APPROVED')],
    });
    const stages = buildTimelineStages(q, false, t);
    const review = stages.find((s) => s.label === 'Human review');
    expect(review).toBeDefined();
    expect(review!.who).toBe('Alice');
    expect(review!.done).toBe(true);
  });

  it('joins multiple approvers with a comma on an EXECUTED query, in decision order', () => {
    const alice = reviewer('r1', 'Alice', 'alice@example.com');
    const bob = reviewer('r2', 'Bob', 'bob@example.com');
    const q = makeQuery({
      status: 'EXECUTED',
      duration_ms: 50,
      review_decisions: [
        decision(alice, 'APPROVED', '2026-05-01T10:00:00Z'),
        decision(bob, 'APPROVED', '2026-05-01T10:05:00Z'),
      ],
    });
    const stages = buildTimelineStages(q, false, t);
    const review = stages.find((s) => s.label === 'Human review');
    expect(review!.who).toBe('Alice, Bob');
  });

  it('falls back to email when approver has no display name', () => {
    const carol = reviewer('r3', null, 'carol@example.com');
    const q = makeQuery({
      status: 'APPROVED',
      review_decisions: [decision(carol, 'APPROVED')],
    });
    const stages = buildTimelineStages(q, false, t);
    const review = stages.find((s) => s.label === 'Human review');
    expect(review!.who).toBe('carol@example.com');
  });

  it('still shows the rejecter name on a REJECTED query (regression guard)', () => {
    const dave = reviewer('r4', 'Dave', 'dave@example.com');
    const q = makeQuery({
      status: 'REJECTED',
      review_decisions: [{ ...decision(dave, 'REJECTED'), comment: 'No way' }],
    });
    const stages = buildTimelineStages(q, false, t);
    const rejected = stages.find((s) => s.label === 'Rejected');
    expect(rejected).toBeDefined();
    expect(rejected!.who).toBe('Dave');
    expect(rejected!.detail).toBe('"No way"');
  });

  it('emits "awaiting reviewer" on PENDING_REVIEW with no decisions', () => {
    const q = makeQuery({ status: 'PENDING_REVIEW' });
    const stages = buildTimelineStages(q, false, t);
    const review = stages.find((s) => s.label === 'Human review');
    expect(review!.who).toBe('awaiting reviewer');
    expect(review!.active).toBe(true);
  });

  it('emits — on TIMED_OUT (done, but no approvers)', () => {
    const q = makeQuery({ status: 'TIMED_OUT' });
    const stages = buildTimelineStages(q, false, t);
    const timed = stages.find((s) => s.label === 'Timed out');
    expect(timed!.who).toBe('—');
    expect(timed!.rejected).toBe(true);
  });
});

describe('buildTimelineStages — Scheduled run stage', () => {
  it('emits a Scheduled run stage between Human review and Execute on an APPROVED scheduled query', () => {
    const q = makeQuery({
      status: 'APPROVED',
      scheduled_for: '2026-05-30T14:00:00Z',
      review_decisions: [
        decision(reviewer('r1', 'Alice', 'alice@example.com'), 'APPROVED'),
      ],
    });
    const stages = buildTimelineStages(q, false, t);
    const labels = stages.map((s) => s.label);
    const reviewIdx = labels.indexOf('Human review');
    const scheduledIdx = labels.indexOf('queries.detail.timeline_scheduled_label');
    const executeIdx = labels.indexOf('Execute');
    expect(scheduledIdx).toBeGreaterThan(-1);
    expect(scheduledIdx).toBeGreaterThan(reviewIdx);
    expect(scheduledIdx).toBeLessThan(executeIdx);
    const scheduled = stages[scheduledIdx]!;
    expect(scheduled.active).toBe(true);
    expect(scheduled.done).toBe(false);
  });

  it('emits no Scheduled run stage on a REJECTED scheduled query', () => {
    const q = makeQuery({
      status: 'REJECTED',
      scheduled_for: '2026-05-30T14:00:00Z',
      review_decisions: [decision(reviewer('r1', 'Dave', 'd@e.com'), 'REJECTED')],
    });
    const stages = buildTimelineStages(q, false, t);
    expect(stages.find((s) => s.label === 'queries.detail.timeline_scheduled_label'))
      .toBeUndefined();
  });

  it('emits no Scheduled run stage on a TIMED_OUT scheduled query', () => {
    const q = makeQuery({
      status: 'TIMED_OUT',
      scheduled_for: '2026-05-30T14:00:00Z',
    });
    const stages = buildTimelineStages(q, false, t);
    expect(stages.find((s) => s.label === 'queries.detail.timeline_scheduled_label'))
      .toBeUndefined();
  });

  it('marks the Scheduled run stage as cancelled on a CANCELLED scheduled query', () => {
    const q = makeQuery({
      status: 'CANCELLED',
      scheduled_for: '2026-05-30T14:00:00Z',
    });
    const stages = buildTimelineStages(q, false, t);
    const scheduled = stages.find(
      (s) => s.label === 'queries.detail.timeline_scheduled_label',
    );
    expect(scheduled!.cancelled).toBe(true);
    expect(scheduled!.done).toBe(false);
    expect(scheduled!.active).toBe(false);
  });

  it('marks the Scheduled run stage as done on an EXECUTED scheduled query', () => {
    const q = makeQuery({
      status: 'EXECUTED',
      scheduled_for: '2026-05-30T14:00:00Z',
      duration_ms: 12,
      review_decisions: [
        decision(reviewer('r1', 'Alice', 'alice@example.com'), 'APPROVED'),
      ],
    });
    const stages = buildTimelineStages(q, false, t);
    const scheduled = stages.find(
      (s) => s.label === 'queries.detail.timeline_scheduled_label',
    );
    expect(scheduled!.done).toBe(true);
  });

  it('marks the Scheduled run stage as failed on a FAILED scheduled query', () => {
    const q = makeQuery({
      status: 'FAILED',
      scheduled_for: '2026-05-30T14:00:00Z',
    });
    const stages = buildTimelineStages(q, false, t);
    const scheduled = stages.find(
      (s) => s.label === 'queries.detail.timeline_scheduled_label',
    );
    expect(scheduled!.failed).toBe(true);
  });
});

describe('buildTimelineStages — AI skipped path', () => {
  it('renders a skipped AI stage when aiSkipped=true', () => {
    const q = makeQuery({ status: 'PENDING_REVIEW' });
    const stages = buildTimelineStages(q, true, t);
    const ai = stages.find((s) => s.label === 'queries.detail.timeline_ai_skipped_label');
    expect(ai).toBeDefined();
    expect(ai!.skipped).toBe(true);
  });

  it('renders the regular AI stage when aiSkipped=false and ai_analysis is null', () => {
    const q = makeQuery({ status: 'PENDING_AI' });
    const stages = buildTimelineStages(q, false, t);
    const ai = stages.find((s) => s.label === 'AI analysis');
    expect(ai).toBeDefined();
    expect(ai!.who).toBe('pending');
    expect(ai!.active).toBe(true);
  });
});

describe('buildTimelineStages — execute stage', () => {
  it('shows rows + duration detail on an EXECUTED query', () => {
    const q = makeQuery({
      status: 'EXECUTED',
      duration_ms: 42,
      rows_affected: 7,
    });
    const stages = buildTimelineStages(q, false, t);
    const exec = stages.find((s) => s.label === 'Execute');
    expect(exec!.done).toBe(true);
    expect(exec!.detail).toContain('rows');
    expect(exec!.detail).toContain('42ms');
  });
});
