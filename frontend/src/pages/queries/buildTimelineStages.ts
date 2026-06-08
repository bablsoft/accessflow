import type { TimelineStage } from '@/components/review/ApprovalTimeline';
import type { QueryDetail } from '@/types/api';
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
      reviewerWho = lastReject
        ? userDisplay(lastReject.reviewer.display_name, lastReject.reviewer.email)
        : '—';
      rejectionDetail = lastReject?.comment ? `"${lastReject.comment}"` : null;
    } else if (reviewDone) {
      const approvers = (query.review_decisions ?? [])
        .filter((d) => d.decision === 'APPROVED')
        .map((d) => userDisplay(d.reviewer.display_name, d.reviewer.email));
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
