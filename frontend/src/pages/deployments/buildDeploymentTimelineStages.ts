import type { TFunction } from 'i18next';
import type { TimelineStage } from '@/components/review/ApprovalTimeline';
import type { DeploymentRequest } from '@/types/api';
import { fmtDate } from '@/utils/dateFormat';
import { deploymentOutcomeLabel, riskLevelLabel } from '@/utils/enumLabels';

const REVIEW_DONE_STATUSES = ['APPROVED', 'EXECUTED', 'FAILED', 'CANCELLED'] as const;

export function buildDeploymentTimelineStages(
  request: DeploymentRequest,
  t: TFunction,
): TimelineStage[] {
  const out: TimelineStage[] = [
    {
      label: t('deploygov.detail.timeline_submitted'),
      who: request.submitted_by_email ?? '—',
      time: request.created_at,
      done: true,
    },
  ];

  if (request.status === 'PENDING_AI') {
    out.push({
      label: t('deploygov.detail.timeline_ai'),
      who: t('deploygov.detail.timeline_ai_analyzing'),
      time: null,
      done: false,
      active: true,
    });
  } else if (request.ai_risk_level != null) {
    out.push({
      label: t('deploygov.detail.timeline_ai'),
      who:
        request.ai_risk_score != null
          ? `${riskLevelLabel(t, request.ai_risk_level)} · ${request.ai_risk_score}`
          : riskLevelLabel(t, request.ai_risk_level),
      time: request.created_at,
      done: true,
      riskLevel: request.ai_risk_level,
    });
  } else {
    out.push({
      label: t('deploygov.detail.timeline_ai'),
      who: t('deploygov.detail.timeline_ai_skipped'),
      time: null,
      done: false,
      skipped: true,
    });
  }

  const rejected = request.status === 'REJECTED' || request.status === 'TIMED_OUT';
  const reviewDone = (REVIEW_DONE_STATUSES as readonly string[]).includes(request.status);
  const approvals = request.decisions.filter((d) => d.decision === 'APPROVED').length;
  let reviewWho: string;
  let reviewDetail: string | null = null;
  if (rejected) {
    const lastReject = [...request.decisions].reverse().find((d) => d.decision === 'REJECTED');
    reviewWho =
      request.status === 'TIMED_OUT' ? t('deploygov.detail.timeline_timed_out_who') : '—';
    reviewDetail = lastReject?.comment ? `"${lastReject.comment}"` : null;
  } else if (reviewDone && request.decisions.length === 0) {
    reviewWho =
      request.submission_reason === 'EMERGENCY_ACCESS'
        ? t('deploygov.detail.timeline_break_glass')
        : t('deploygov.detail.timeline_auto_approved');
  } else if (reviewDone) {
    reviewWho = t('deploygov.detail.timeline_approvals_who', {
      granted: approvals,
      required: request.required_approvals,
    });
  } else {
    reviewWho = t('deploygov.detail.timeline_awaiting_reviewer');
  }
  out.push({
    label: rejected
      ? request.status === 'TIMED_OUT'
        ? t('deploygov.detail.timeline_timed_out')
        : t('deploygov.detail.timeline_rejected')
      : t('deploygov.detail.timeline_review'),
    who: reviewWho,
    time: null,
    done: reviewDone,
    active: request.status === 'PENDING_REVIEW',
    rejected,
    detail: reviewDetail,
  });
  if (rejected) return out;

  if (request.scheduled_for != null) {
    out.push({
      label: t('deploygov.detail.timeline_scheduled'),
      who: fmtDate(request.scheduled_for),
      time: null,
      done: request.status === 'EXECUTED' || request.outcome != null,
      active: request.status === 'APPROVED',
      cancelled: request.status === 'CANCELLED',
    });
  }

  const executed = request.status === 'EXECUTED' || request.outcome_reported_at != null;
  const executionFailed = request.status === 'FAILED' && request.outcome == null;
  out.push({
    label:
      request.status === 'CANCELLED'
        ? t('deploygov.detail.timeline_cancelled')
        : executionFailed
        ? t('deploygov.detail.timeline_execution_failed')
        : t('deploygov.detail.timeline_released'),
    who: request.pipeline_name ?? '—',
    time: null,
    done: executed,
    active: request.status === 'APPROVED' && request.scheduled_for == null,
    failed: executionFailed,
    cancelled: request.status === 'CANCELLED',
  });

  if (request.outcome != null) {
    out.push({
      label: t('deploygov.detail.timeline_outcome'),
      who: deploymentOutcomeLabel(t, request.outcome),
      time: request.outcome_reported_at,
      done: request.outcome === 'SUCCEEDED',
      failed: request.outcome === 'FAILED' || request.outcome === 'ROLLED_BACK',
      detail: request.outcome_detail,
    });
  }
  return out;
}
