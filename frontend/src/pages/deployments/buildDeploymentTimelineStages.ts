import type { TFunction } from 'i18next';
import type { TimelineStage } from '@/components/review/ApprovalTimeline';
import type { DeploymentRequest, VerificationStatus } from '@/types/api';
import { fmtDate } from '@/utils/dateFormat';
import {
  deploymentOutcomeLabel,
  riskLevelLabel,
  verificationStatusLabel,
} from '@/utils/enumLabels';

const SKIPPED_OUTCOME_PREFIX = 'outcome:';

/**
 * The backend's `verification_detail` for a SKIPPED request is a machine token
 * (`no verification configured`, `outcome:FAILED`, `outcome:ROLLED_BACK` — #1025), so it is
 * translated here; a breach detail (`<check>: n/threshold failed samples`) is shown as sent.
 */
export function verificationDetailText(
  t: TFunction,
  status: VerificationStatus,
  detail: string | null | undefined,
): string | null {
  if (detail == null || detail === '') return null;
  if (status !== 'SKIPPED') return detail;
  if (detail === 'no verification configured') {
    return t('deploygov.detail.verificationSkippedNoConfig');
  }
  if (detail.startsWith(SKIPPED_OUTCOME_PREFIX)) {
    const outcome = detail.slice(SKIPPED_OUTCOME_PREFIX.length);
    if (outcome === 'FAILED' || outcome === 'ROLLED_BACK' || outcome === 'SUCCEEDED') {
      return t('deploygov.detail.verificationSkippedOutcome', {
        outcome: deploymentOutcomeLabel(t, outcome),
      });
    }
  }
  return detail;
}

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
  // A verification breach (#1025) is FAILED with a SUCCEEDED outcome: the release did run, so
  // only a FAILED request with no outcome at all reads as an execution failure here.
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

  // Starts on the outcome report, so it sits after the outcome stage; nothing to show before.
  const verification = request.verification_status;
  if (verification != null && verification !== 'PENDING') {
    out.push({
      label: t('deploygov.detail.timeline_verification'),
      who: verificationStatusLabel(t, verification),
      time: request.verification_finished_at ?? null,
      done: verification === 'PASSED',
      active: verification === 'RUNNING',
      failed: verification === 'FAILED',
      skipped: verification === 'SKIPPED',
      detail: verificationDetailText(t, verification, request.verification_detail),
    });
  }
  return out;
}
