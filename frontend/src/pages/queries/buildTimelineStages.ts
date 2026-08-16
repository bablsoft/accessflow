import type { TimelineStage } from '@/components/review/ApprovalTimeline';
import type { QueryDetail, ReviewDecisionDetail } from '@/types/api';
import { fmtDate, fmtNum } from '@/utils/dateFormat';
import { userDisplay } from '@/utils/userDisplay';

export function buildTimelineStages(
  query: QueryDetail,
  aiSkipped: boolean,
  t: (key: string) => string,
): TimelineStage[] {
  const out: TimelineStage[] = [
    {
      label: 'Submitted',
      who: userDisplay(query.submitted_by.display_name, query.submitted_by.email),
      time: query.created_at,
      done: true,
    },
  ];
  const aiFailed = query.ai_analysis?.failed === true;
  if (aiSkipped) {
    out.push({
      label: t('queries.detail.timeline_ai_skipped_label'),
      who: t('queries.detail.timeline_ai_skipped_who'),
      time: query.created_at,
      done: false,
      active: false,
      skipped: true,
      detail: null,
      riskLevel: null,
    });
  } else {
    out.push({
      label: aiFailed ? 'AI analysis failed' : 'AI analysis',
      who: query.ai_analysis
        ? `${query.ai_analysis.ai_provider.toLowerCase()} / ${query.ai_analysis.ai_model}`
        : 'pending',
      time: query.ai_analysis ? query.created_at : null,
      done:
        !aiFailed &&
        ['PENDING_REVIEW', 'APPROVED', 'EXECUTED', 'REJECTED', 'TIMED_OUT', 'FAILED'].includes(
          query.status,
        ),
      active: query.status === 'PENDING_AI',
      failed: aiFailed,
      detail: aiFailed
        ? query.ai_analysis?.error_message ?? 'failed'
        : query.ai_analysis
        ? `${query.ai_analysis.risk_level} · score ${query.ai_analysis.risk_score}`
        : 'analyzing…',
      riskLevel: aiFailed ? null : query.ai_analysis?.risk_level ?? null,
    });
  }
  if (query.status !== 'APPROVED' || query.duration_ms == null) {
    const reviewDone = ['APPROVED', 'EXECUTED', 'REJECTED', 'TIMED_OUT'].includes(query.status);
    const reviewLabel =
      query.status === 'REJECTED'
        ? 'Rejected'
        : query.status === 'TIMED_OUT'
        ? 'Timed out'
        : 'Human review';
    let reviewerWho: string;
    let rejectionDetail: string | null = null;
    if (query.status === 'REJECTED') {
      const decisions = query.review_decisions ?? [];
      const lastReject = [...decisions]
        .reverse()
        .find((d) => d.decision === 'REJECTED');
      reviewerWho = lastReject ? decisionWho(lastReject) : '—';
      rejectionDetail = lastReject?.comment ? `"${lastReject.comment}"` : null;
    } else if (reviewDone) {
      const approvers = (query.review_decisions ?? [])
        .filter((d) => d.decision === 'APPROVED')
        .map(decisionWho);
      reviewerWho = approvers.length > 0 ? approvers.join(', ') : '—';
    } else {
      reviewerWho = 'awaiting reviewer';
    }
    out.push({
      label: reviewLabel,
      who: reviewerWho,
      time: reviewDone ? query.updated_at : null,
      done: reviewDone,
      active: query.status === 'PENDING_REVIEW',
      rejected: query.status === 'REJECTED' || query.status === 'TIMED_OUT',
      detail: rejectionDetail,
    });
  }
  const showScheduledStage =
    query.scheduled_for != null &&
    query.status !== 'REJECTED' &&
    query.status !== 'TIMED_OUT';
  if (showScheduledStage) {
    out.push({
      label: t('queries.detail.timeline_scheduled_label'),
      who: fmtDate(query.scheduled_for!),
      time: null,
      done: query.status === 'EXECUTED',
      active: query.status === 'APPROVED',
      cancelled: query.status === 'CANCELLED',
      failed: query.status === 'FAILED',
    });
  }
  // #627: a recurring parent never reaches EXECUTED itself — its occurrences do. Replace the
  // Execute stage with a series stage reflecting the derived state (active/completed/halted).
  const isRecurringParent = query.recurrence_rule != null;
  if (isRecurringParent && query.status !== 'REJECTED' && query.status !== 'TIMED_OUT') {
    const halted = query.recurrence_halted_reason != null;
    const completed =
      query.status === 'APPROVED' && !halted && query.recurrence_next_run_at == null;
    out.push({
      label: halted
        ? t('queries.detail.timeline_recurring_halted_label')
        : completed
        ? t('queries.detail.timeline_recurring_completed_label')
        : t('queries.detail.timeline_recurring_label'),
      who: query.recurrence_rule ?? '—',
      time: null,
      done: completed,
      active: query.status === 'APPROVED' && !halted && query.recurrence_next_run_at != null,
      cancelled: query.status === 'CANCELLED',
      failed: halted,
      detail: halted
        ? query.recurrence_halted_reason
        : query.recurrence_next_run_at
        ? `${t('queries.detail.timeline_recurring_next_prefix')} ${fmtDate(
            query.recurrence_next_run_at,
          )}`
        : null,
    });
    return out;
  }
  if (query.status !== 'REJECTED' && query.status !== 'TIMED_OUT') {
    out.push({
      label:
        query.status === 'FAILED'
          ? 'Execution failed'
          : query.status === 'CANCELLED'
          ? 'Cancelled'
          : 'Execute',
      who: query.status === 'EXECUTED' ? `proxy → ${query.datasource.name}` : '—',
      time: query.status === 'EXECUTED' ? query.updated_at : null,
      done: query.status === 'EXECUTED',
      failed: query.status === 'FAILED',
      cancelled: query.status === 'CANCELLED',
      detail:
        query.status === 'EXECUTED' && query.duration_ms != null
          ? `${fmtNum(query.rows_affected)} rows · ${query.duration_ms}ms`
          : query.status === 'FAILED'
          ? query.error_message ?? null
          : null,
    });
  }
  return out;
}

/**
 * Who a decision is attributed to. A decision taken under an out-of-office delegation (#622) names
 * both parties, so the timeline never implies the delegator acted themselves.
 */
function decisionWho(decision: ReviewDecisionDetail): string {
  const actor = userDisplay(decision.reviewer.display_name, decision.reviewer.email);
  if (!decision.on_behalf_of) return actor;
  const delegator = userDisplay(
    decision.on_behalf_of.display_name,
    decision.on_behalf_of.email,
  );
  return `${actor} (on behalf of ${delegator})`;
}
