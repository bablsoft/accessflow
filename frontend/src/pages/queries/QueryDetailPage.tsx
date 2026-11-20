import { dataBudgetKeys } from '@/api/dataBudgets';
import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { hasPermission } from '@/utils/permissions';
import { Alert, App, Button, Form, Input, Modal, Popconfirm, Select, Skeleton } from 'antd';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  ClockCircleOutlined,
  CloseOutlined,
  CopyOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  ExperimentOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  InfoCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SyncOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { DataTable, Page } from '@/components/layout';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { DetailCard } from '@/components/common/DetailCard';
import { ApprovalTimeline, type TimelineStage } from '@/components/review/ApprovalTimeline';
import { CostEstimatePanel } from '@/components/review/CostEstimatePanel';
import { ApprovalPredictionPanel } from '@/components/review/ApprovalPredictionPanel';
import { SqlReviewFindingList } from '@/components/review/SqlReviewFindingList';
import { countBlockingFindings } from '@/utils/sqlReview';
import { IssueCard } from '@/components/editor/IssueCard';
import { OptimizationCard } from '@/components/editor/OptimizationCard';
import { QueryCollaboration } from '@/components/editor/QueryCollaboration';
import { QueryResultsTable } from '@/components/queries/QueryResultsTable';
import { useAuthStore } from '@/store/authStore';
import {
  bytesScannedCapOutcomeLabel,
  bytesScannedCapSourceLabel,
  channelTypeLabel,
  routingActionLabel,
  decisionHookFailureLabel,
  decisionHookOutcomeLabel,
} from '@/utils/enumLabels';
import { formatBytes } from '@/utils/queryPlan';
import { fmtDate, fmtNum, timeAgo } from '@/utils/dateFormat';
import { engineMode } from '@/utils/engineModes';
import {
  cancelQuery,
  executeQuery,
  getQuery,
  listOccurrences,
  queryKeys,
  reanalyzeQuery,
  replayQuery,
} from '@/api/queries';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import {
  approveQuery,
  rejectQuery,
  requestChanges,
  reviewKeys,
} from '@/api/reviews';
import { apiErrorMessage, queryCancelErrorMessage, queryReplayErrorMessage, reviewErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { userDisplay } from '@/utils/userDisplay';
import type { LinkedTicketRef, QueryDetail, QueryOccurrence } from '@/types/api';
import { QueryDiffCard } from './QueryDiffCard';
import { QuerySqlView } from './QuerySqlView';
import { buildTimelineStages } from './buildTimelineStages';
import './query-detail.css';
import { OnBehalfOfTag } from '@/components/common/OnBehalfOfTag';
import { ClientApplicationTag } from '@/components/common/ClientApplicationTag';

interface QueryNotice {
  key: string;
  severity: 'info' | 'success' | 'warning' | 'error';
  /** Forces a banner for a notice the viewer must act on, whatever its severity. */
  banner?: boolean;
  icon?: ReactNode;
  testId?: string;
  title: string;
  description?: string;
  action?: ReactNode;
}

export function QueryDetailPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const { message } = App.useApp();
  const [comment, setComment] = useState('');
  const [replayOpen, setReplayOpen] = useState(false);
  const [replayTarget, setReplayTarget] = useState<string | undefined>(undefined);
  const queryClient = useQueryClient();

  const { data: query, isLoading } = useQuery({
    queryKey: queryKeys.detail(id ?? ''),
    queryFn: () => getQuery(id!),
    enabled: !!id,
    // #627: an active recurring series changes server-side on every occurrence — keep the
    // next-run cursor and derived series state fresh while the user watches the page.
    refetchInterval: (q) =>
      q.state.data?.recurrence_next_run_at != null ? 15_000 : false,
  });

  const cancelMutation = useMutation({
    mutationFn: () => cancelQuery(id!),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.detail(id!) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      message.info(t('queries.detail.on_cancel_success'));
    },
    onError: (err) => showApiError(message, err, queryCancelErrorMessage),
  });

  const executeMutation = useMutation({
    mutationFn: () => executeQuery(id!),
    onSuccess: (data) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.detail(id!) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      // #942: a read spends the data budget; the editor's remaining allowance is now stale.
      void queryClient.invalidateQueries({ queryKey: dataBudgetKeys.all });
      if (data.status === 'EXECUTED') {
        message.success(t('queries.detail.on_execute_success'));
      } else {
        message.error(t('queries.detail.on_execute_error'));
      }
    },
    onError: (err) =>
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('queries.detail.on_execute_error')),
      ),
  });

  const { data: replayDatasources } = useQuery({
    queryKey: datasourceKeys.list({ size: 100 }),
    queryFn: () => listDatasources({ size: 100 }),
    enabled: replayOpen,
  });

  const replayMutation = useMutation({
    mutationFn: (targetDatasourceId: string) => replayQuery(id!, targetDatasourceId),
    onSuccess: (data) => {
      setReplayOpen(false);
      setReplayTarget(undefined);
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      message.success(t('queries.detail.replay_success'));
      navigate(`/queries/${data.id}`);
    },
    onError: (err) => showApiError(message, err, queryReplayErrorMessage),
  });

  const reanalyzeMutation = useMutation({
    mutationFn: () => reanalyzeQuery(id!),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.detail(id!) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      message.success(t('queries.detail.ai_failed_reanalyze_success'));
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('queries.detail.ai_failed_reanalyze_error')));
    },
  });

  const invalidateAfterDecision = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.detail(id!) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
    void queryClient.invalidateQueries({ queryKey: reviewKeys.all });
  };

  const approveMutation = useMutation({
    mutationFn: () => approveQuery(id!, comment.trim() || undefined),
    onSuccess: () => {
      invalidateAfterDecision();
      setComment('');
      message.success(t('queries.detail.on_approve_success'));
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  const rejectMutation = useMutation({
    mutationFn: () => rejectQuery(id!, comment.trim()),
    onSuccess: () => {
      invalidateAfterDecision();
      setComment('');
      message.success(t('queries.detail.on_reject_success'));
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  const requestChangesMutation = useMutation({
    mutationFn: () => requestChanges(id!, comment.trim()),
    onSuccess: () => {
      invalidateAfterDecision();
      setComment('');
      message.success(t('queries.detail.on_request_changes_success'));
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  const aiSkipped = !!query && !query.ai_analysis && query.status !== 'PENDING_AI';

  const latestDecision = useMemo(() => {
    const decisions = query?.review_decisions ?? [];
    return decisions.length > 0 ? decisions[decisions.length - 1] : null;
  }, [query]);
  const changesRequested =
    !!query &&
    query.status === 'PENDING_REVIEW' &&
    latestDecision?.decision === 'REQUESTED_CHANGES';

  const stages: TimelineStage[] = useMemo(() => {
    if (!query) return [];
    return buildTimelineStages(query, aiSkipped, t);
  }, [query, aiSkipped, t]);

  if (isLoading) {
    return (
      <div className="u-p-gutter">
        <Skeleton active paragraph={{ rows: 8 }} />
      </div>
    );
  }
  if (!query || !user) {
    return (
      <div className="u-p-10 u-text-center">
        <div className="muted">{t('queries.detail.not_found_message')}</div>
        <Button onClick={() => navigate('/queries')} className="u-mt-3">
          {t('queries.detail.back_to_history')}
        </Button>
      </div>
    );
  }

  const isReviewer = hasPermission(user, 'QUERY_REVIEW');
  const submitterId = query.submitted_by.id;
  const canDecide = isReviewer && query.status === 'PENDING_REVIEW' && submitterId !== user.id;
  // Deliberately not canDecide — the card stays useful after a decision lands; it is only the
  // submitter that must never see it. See the card's comment below.
  const canSeeApprovalPrediction = isReviewer && submitterId !== user.id;
  const hasScheduledRun =
    query.status === 'APPROVED' && !!query.scheduled_for;
  const showScheduledBanner =
    !!query.scheduled_for &&
    (query.status === 'PENDING_AI' ||
      query.status === 'PENDING_REVIEW' ||
      query.status === 'APPROVED');
  // #627 recurring series: parents carry recurrence_rule; occurrence rows carry
  // recurring_parent_id. Series state is derived — no dedicated status value exists.
  const isRecurringParent = !!query.recurrence_rule;
  const hasActiveSeries =
    query.status === 'APPROVED' && isRecurringParent && !!query.recurrence_next_run_at;
  const seriesState: 'active' | 'completed' | 'halted' | 'cancelled' | null = !isRecurringParent
    ? null
    : query.status === 'CANCELLED'
      ? 'cancelled'
      : query.recurrence_halted_reason
        ? 'halted'
        : query.status === 'APPROVED'
          ? query.recurrence_next_run_at
            ? 'active'
            : 'completed'
          : null;
  const cancellableState =
    query.status === 'PENDING_AI' ||
    query.status === 'PENDING_REVIEW' ||
    hasScheduledRun ||
    hasActiveSeries;
  // The submitter may always cancel; a reviewer only a recurring series (the #627 kill-switch).
  const canCancel =
    cancellableState && (submitterId === user.id || (isReviewer && isRecurringParent));
  // A recurring parent must stay APPROVED — manual execution is blocked (409 server-side too).
  const canExecute =
    query.status === 'APPROVED' &&
    !isRecurringParent &&
    (submitterId === user.id || hasPermission(user, 'QUERY_ADMIN'));
  // Replay is offered once a query has executed; its immutable snapshot exists from then on.
  const canReplay = query.status === 'EXECUTED';
  const replayTargets = (replayDatasources?.content ?? []).filter(
    (d) => d.active && d.id !== query.datasource.id && d.db_type === query.db_type,
  );
  const aiFailed = query.ai_analysis?.failed === true;
  const aiFailureReason = query.ai_analysis?.error_message ?? '';
  const sqlReviewFindings = query.sql_review_findings ?? [];
  const sqlReviewBlocking = countBlockingFindings(sqlReviewFindings);
  const canReanalyze = isReviewer && aiFailed && query.status === 'PENDING_REVIEW';
  // Live co-authoring is offered while the query is in review to the submitter and any
  // reviewer/admin; the backend confirms assigned-reviewer eligibility on join.
  const canCollaborate =
    query.status === 'PENDING_REVIEW' && (submitterId === user.id || isReviewer);

  // Every notice about how this request was scheduled, routed and decided. Problems and anything
  // the viewer must act on stay banners above the SQL; the rest is context and goes into one
  // compact card in the side column.
  const notices: QueryNotice[] = [];
  if (showScheduledBanner) {
    notices.push({
      key: 'scheduled',
      severity: 'info',
      icon: <ClockCircleOutlined />,
      title: hasScheduledRun
        ? t('queries.detail.scheduled_banner_title')
        : t('queries.detail.scheduled_banner_title_pending'),
      description: hasScheduledRun
        ? t('queries.detail.scheduled_banner_body', { when: fmtDate(query.scheduled_for!) })
        : t('queries.detail.scheduled_banner_body_pending', { when: fmtDate(query.scheduled_for!) }),
    });
  }
  if (isRecurringParent && seriesState) {
    notices.push({
      key: 'series',
      severity: seriesState === 'halted' ? 'warning' : 'info',
      icon: <SyncOutlined />,
      testId: 'recurrence-banner',
      title: t(`queries.detail.series_banner_title_${seriesState}`),
      description:
        seriesState === 'active'
          ? t('queries.detail.series_banner_body_active', {
              rule: query.recurrence_rule,
              next: fmtDate(query.recurrence_next_run_at!),
              until: fmtDate(query.recurrence_until!),
            })
          : seriesState === 'halted'
            ? t('queries.detail.series_banner_body_halted', {
                reason: query.recurrence_halted_reason ?? '—',
              })
            : t('queries.detail.series_banner_body_ended'),
    });
  }
  if (query.recurring_parent_id) {
    notices.push({
      key: 'occurrence',
      severity: 'info',
      icon: <SyncOutlined />,
      testId: 'occurrence-banner',
      title: t('queries.detail.occurrence_banner_title'),
      action: (
        <Button size="small" onClick={() => navigate(`/queries/${query.recurring_parent_id}`)}>
          {t('queries.detail.occurrence_banner_link')}
        </Button>
      ),
    });
  }
  if (changesRequested && latestDecision) {
    notices.push({
      key: 'changes_requested',
      severity: 'info',
      banner: true,
      icon: <EditOutlined />,
      title: t('queries.detail.changes_requested_banner_title'),
      description: t('queries.detail.changes_requested_banner_body', {
        reviewer: userDisplay(latestDecision.reviewer.display_name, latestDecision.reviewer.email),
        when: timeAgo(latestDecision.decided_at),
        comment: latestDecision.comment ?? '',
      }),
    });
  }
  if (aiFailed) {
    notices.push({
      key: 'ai_failed',
      severity: 'warning',
      icon: <ExclamationCircleOutlined />,
      title: t('queries.detail.ai_failed_banner_title'),
      description: t('queries.detail.ai_failed_banner_detail', { reason: aiFailureReason || '—' }),
      action: canReanalyze ? (
        <Button
          size="small"
          icon={<ReloadOutlined />}
          loading={reanalyzeMutation.isPending}
          onClick={() => reanalyzeMutation.mutate()}
        >
          {reanalyzeMutation.isPending
            ? t('queries.detail.ai_failed_reanalyze_pending')
            : t('queries.detail.ai_failed_reanalyze')}
        </Button>
      ) : undefined,
    });
  }
  if (query.status === 'TIMED_OUT') {
    notices.push({
      key: 'timed_out',
      severity: 'warning',
      icon: <ClockCircleOutlined />,
      title: t('queries.detail.timeout_title'),
      description: query.review_plan_name
        ? t('queries.detail.timeout_body_with_plan', {
            plan: query.review_plan_name,
            hours: query.approval_timeout_hours ?? '?',
            when: timeAgo(query.updated_at),
          })
        : t('queries.detail.timeout_body_without_plan', {
            hours: query.approval_timeout_hours ?? '?',
            when: timeAgo(query.updated_at),
          }),
    });
  }
  if (query.escalated_at && query.status === 'PENDING_REVIEW') {
    notices.push({
      key: 'escalated',
      severity: 'warning',
      icon: <ClockCircleOutlined />,
      testId: 'escalation-banner',
      title: t('queries.detail.escalated_title'),
      description: t('queries.detail.escalated_body', {
        hours: query.escalation_after_hours ?? '?',
        when: timeAgo(query.escalated_at),
      }),
    });
  }
  if (query.matched_policy && query.matched_policy.source !== 'DECISION_HOOK') {
    notices.push({
      key: 'matched_policy',
      severity: 'info',
      title: t('queries.detail.matched_policy_title', {
        action: routingActionLabel(t, query.matched_policy.action),
      }),
      description: t('queries.detail.matched_policy_body', {
        name: query.matched_policy.policy_name ?? t('queries.detail.matched_policy_deleted'),
        reason: query.matched_policy.reason ?? t('queries.detail.matched_policy_no_reason'),
      }),
    });
  }
  if (query.decision_hook) {
    notices.push({
      key: 'decision_hook',
      severity: query.decision_hook.outcome === 'FAILED' ? 'warning' : 'info',
      testId: 'decision-hook-alert',
      title: t('queries.detail.decision_hook_title', {
        name: query.decision_hook.decision_hook_name,
        outcome: decisionHookOutcomeLabel(t, query.decision_hook.outcome),
      }),
      description:
        query.decision_hook.outcome === 'FAILED' && query.decision_hook.failure
          ? t('queries.detail.decision_hook_failed_body', {
              failure: decisionHookFailureLabel(t, query.decision_hook.failure),
            })
          : t('queries.detail.decision_hook_body', {
              reason: query.decision_hook.reason ?? t('queries.detail.matched_policy_no_reason'),
            }),
    });
  }
  if (query.bytes_scanned_cap) {
    const outcome = query.bytes_scanned_cap.outcome;
    notices.push({
      key: 'bytes_cap',
      severity:
        outcome === 'EXCEEDED' || outcome === 'NO_ESTIMATE_REJECTED'
          ? 'error'
          : outcome === 'NO_ESTIMATE_REVIEW'
            ? 'warning'
            : 'info',
      testId: 'bytes-cap-banner',
      title: bytesScannedCapOutcomeLabel(t, outcome),
      description: t('queries.detail.bytes_cap_body', {
        estimate:
          formatBytes(query.cost_estimate?.estimated_bytes_scanned) ??
          t('queries.detail.bytes_cap_no_estimate'),
        limit: formatBytes(query.bytes_scanned_cap.limit),
        source: bytesScannedCapSourceLabel(t, query.bytes_scanned_cap.source),
      }),
    });
  }
  if (query.approved_by_grant) {
    notices.push({
      key: 'approved_by_grant',
      severity: 'success',
      title: t('queries.detail.approved_by_grant_title'),
      description: query.approved_by_grant.approver_email
        ? t('queries.detail.approved_by_grant_body', {
            grantId: query.approved_by_grant.grant_id,
            approver: query.approved_by_grant.approver_email,
            when: query.approved_by_grant.approved_at
              ? fmtDate(query.approved_by_grant.approved_at)
              : '—',
          })
        : t('queries.detail.approved_by_grant_body_no_approver', {
            grantId: query.approved_by_grant.grant_id,
          }),
    });
  }
  const isBanner = (n: QueryNotice) =>
    n.banner === true || n.severity === 'warning' || n.severity === 'error';
  const banners = notices.filter(isBanner);
  const contextNotices = notices.filter((n) => !isBanner(n));

  return (
    <Page>
      <PageHeader
        breadcrumbs={[t('queries.detail.breadcrumb_queries'), query.id]}
        title={
          <span className="u-inline-flex u-items-center u-gap-3">
            <span className="mono">{query.id}</span>
            <StatusPill status={query.status} />
          </span>
        }
        subtitle={
          <>
            {t('queries.detail.submitted_by')} <strong>{query.submitted_by.display_name}</strong>{' '}
            {query.on_behalf_of && (
              <>
                <OnBehalfOfTag email={query.on_behalf_of.email} userId={query.on_behalf_of.id} />{' '}
              </>
            )}
            · {fmtDate(query.created_at)} ·{' '}
            <span className="mono">{query.datasource.name}</span>
            {query.application_name && (
              <>
                {' '}
                · {t('client_application.label')}{' '}
                <ClientApplicationTag
                  name={query.application_name}
                  source={query.application_name_source}
                  testId="query-application"
                />
              </>
            )}
          </>
        }
        actions={
          <>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/queries')}>
              {t('common.back')}
            </Button>
            <Button
              icon={<CopyOutlined />}
              onClick={() =>
                navigate('/editor', {
                  state: { presetSql: query.sql_text, datasourceId: query.datasource.id },
                })
              }
            >
              {t('common.duplicate')}
            </Button>
            {canCancel && (
              <Popconfirm
                title={
                  isRecurringParent
                    ? t('queries.detail.cancel_series_confirm_title')
                    : hasScheduledRun
                      ? t('queries.detail.cancel_schedule_confirm_title')
                      : t('queries.detail.cancel_confirm_title')
                }
                description={
                  isRecurringParent
                    ? t('queries.detail.cancel_series_confirm_body')
                    : hasScheduledRun
                      ? t('queries.detail.cancel_schedule_confirm_body')
                      : t('queries.detail.cancel_confirm_body')
                }
                okText={t('common.ok')}
                okButtonProps={{ danger: true }}
                cancelText={t('common.cancel')}
                onConfirm={() => cancelMutation.mutate()}
              >
                <Button
                  danger
                  icon={<CloseOutlined />}
                  loading={cancelMutation.isPending}
                >
                  {isRecurringParent
                    ? t('queries.detail.cancel_series')
                    : hasScheduledRun
                      ? t('queries.detail.cancel_schedule')
                      : t('queries.detail.cancel_query')}
                </Button>
              </Popconfirm>
            )}
            {canReplay && (
              <Button
                icon={<ExperimentOutlined />}
                onClick={() => setReplayOpen(true)}
              >
                {t('queries.detail.replay_button')}
              </Button>
            )}
            {canExecute && (
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                loading={executeMutation.isPending}
                onClick={() => executeMutation.mutate()}
              >
                {t('queries.detail.execute_button')}
              </Button>
            )}
          </>
        }
      />
      <Modal
        title={t('queries.detail.replay_modal_title')}
        open={replayOpen}
        onCancel={() => {
          setReplayOpen(false);
          setReplayTarget(undefined);
        }}
        onOk={() => replayTarget && replayMutation.mutate(replayTarget)}
        okText={t('queries.detail.replay_submit')}
        okButtonProps={{ disabled: !replayTarget, loading: replayMutation.isPending }}
        cancelText={t('common.cancel')}
        destroyOnHidden
      >
        <p>{t('queries.detail.replay_modal_body')}</p>
        {replayTargets.length === 0 ? (
          <EmptyState size="sm" title={t('queries.detail.replay_no_targets')} />
        ) : (
          <Form layout="vertical">
            <Form.Item label={t('queries.detail.replay_target_label')} required>
              <Select
                aria-label={t('queries.detail.replay_target_label')}
                placeholder={t('queries.detail.replay_target_placeholder')}
                value={replayTarget}
                onChange={setReplayTarget}
                options={replayTargets.map((d) => ({ value: d.id, label: d.name }))}
                className="u-w-full"
              />
            </Form.Item>
          </Form>
        )}
      </Modal>
      <div className="qd-grid">
        <div className="qd-main">
          {banners.map((n) => (
            <Alert
              key={n.key}
              type={n.severity}
              showIcon
              icon={n.icon}
              data-testid={n.testId}
              title={n.title}
              description={n.description}
              action={n.action}
            />
          ))}
          <DetailCard
            title={canCollaborate ? t('queries.detail.card_sql_collab') : t('queries.detail.card_sql')}
            icon={<FileTextOutlined />}
            extra={<QueryTypePill type={query.query_type} />}
          >
            {canCollaborate ? (
              <QueryCollaboration query={query} currentUser={user} />
            ) : (
              <div style={{ padding: 14 }}>
                <QuerySqlView
                  sql={query.sql_text}
                  effectiveSql={query.effective_sql}
                  dbType={query.db_type}
                />
              </div>
            )}
          </DetailCard>

          <DetailCard title={t('queries.detail.card_justification')} icon={<InfoCircleOutlined />}>
            <div className="u-text-sm" style={{ padding: 14, lineHeight: 1.55 }}>
              {query.justification?.trim() ? (
                query.justification
              ) : (
                <span className="muted" style={{ fontStyle: 'italic' }}>
                  {t('queries.detail.no_justification')}
                </span>
              )}
            </div>
          </DetailCard>

          <DetailCard
            title={
              aiFailed
                ? t('queries.detail.ai_failed_accordion_title')
                : aiSkipped
                  ? t('queries.detail.card_ai_skipped')
                  : t('queries.detail.card_ai')
            }
            icon={<ThunderboltOutlined className="u-fg-accent" />}
            extra={
              query.ai_analysis ? (
                <>
                  <RiskPill
                    level={query.ai_analysis.risk_level}
                    score={query.ai_analysis.risk_score}
                    failed={aiFailed}
                  />
                  {!aiFailed && (
                    <span className="mono muted u-ml-auto u-text-2xs">
                      {query.ai_analysis.ai_provider.toLowerCase()} · {query.ai_analysis.ai_model}
                    </span>
                  )}
                </>
              ) : null
            }
          >
            {aiFailed ? (
              <div className="u-text-sm" style={{ padding: 14, lineHeight: 1.55 }}>
                <div className="u-mb-3">
                  {t('queries.detail.ai_failed_accordion_body')}
                </div>
                <div
                  className="u-mb-3 u-mono u-text-xs u-bg-sunken u-border u-rounded-sm u-py-2 u-px-3"
                >
                  <span className="muted u-mr-2">
                    {t('queries.detail.ai_failed_reason_label')}:
                  </span>
                  {aiFailureReason || '—'}
                </div>
                {canReanalyze && (
                  <Button
                    type="primary"
                    icon={<ReloadOutlined />}
                    loading={reanalyzeMutation.isPending}
                    onClick={() => reanalyzeMutation.mutate()}
                  >
                    {reanalyzeMutation.isPending
                      ? t('queries.detail.ai_failed_reanalyze_pending')
                      : t('queries.detail.ai_failed_reanalyze')}
                  </Button>
                )}
              </div>
            ) : (
              <>
                <div className="u-text-sm" style={{ padding: 14, lineHeight: 1.55 }}>
                  {query.ai_analysis?.summary ?? (
                    <span className="muted">
                      {aiSkipped
                        ? t('queries.detail.ai_skipped_body')
                        : t('queries.detail.ai_awaiting')}
                    </span>
                  )}
                </div>
                {query.ai_analysis && query.ai_analysis.issues.length > 0 && (
                  <div
                    className="u-flex u-col u-gap-2" style={{ padding: '0 14px 14px' }}
                  >
                    {query.ai_analysis.issues.map((iss, i) => (
                      <IssueCard key={i} issue={iss} />
                    ))}
                  </div>
                )}
                {query.ai_analysis && query.ai_analysis.optimizations.length > 0 && (
                  <div
                    className="u-flex u-col u-gap-2" style={{ padding: '0 14px 14px' }}
                  >
                    <div
                      className="muted u-text-2xs u-fw-500 u-uppercase u-tracking-wide"
                    >
                      {t('queries.detail.optimizations_title')}
                    </div>
                    {query.ai_analysis.optimizations.map((opt, i) => (
                      <OptimizationCard key={i} optimization={opt} />
                    ))}
                  </div>
                )}
              </>
            )}
          </DetailCard>

          <DetailCard
            title={t('queries.detail.card_cost_estimate')}
            icon={<ExperimentOutlined className="u-fg-accent" />}
          >
            <CostEstimatePanel estimate={query.cost_estimate} status={query.status} />
          </DetailCard>

          {/* Deterministic SQL review findings recorded at submission (#864/#865): shown only when
              a rule fired, so a clean query keeps its page short. A BLOCK is why the query could
              not auto-approve — it escalated to this reviewer, it never rejected. */}
          {sqlReviewFindings.length > 0 && (
            <DetailCard
              title={t('queries.detail.card_sql_review')}
              icon={<FileSearchOutlined className="u-fg-accent" />}
            >
              <div data-testid="sql-review-findings-card" className="u-flex u-col" style={{ gap: 10 }}>
                {sqlReviewBlocking > 0 && (
                  <div className="muted u-text-xs">
                    {t('queries.detail.sql_review_blocked_note', { count: sqlReviewBlocking })}
                  </div>
                )}
                <SqlReviewFindingList findings={sqlReviewFindings} />
              </div>
            </DetailCard>
          )}

          {/* Reviewers deciding someone else's query only: the prediction is a triage aid for
              whoever decides, and showing a submitter how their peers are likely to vote on their
              own open request invites cancel-and-resubmit gaming. The backend applies the same rule
              — QueryReadController drops the block from the response for the submitter and for
              anyone without QUERY_REVIEW — so this gate is defence in depth, not the only one. */}
          {canSeeApprovalPrediction && (
            <DetailCard
              title={t('queries.detail.card_approval_prediction')}
              icon={<InfoCircleOutlined className="u-fg-accent" />}
            >
              <ApprovalPredictionPanel
                prediction={query.approval_prediction}
                status={query.status}
                updatedAt={query.updated_at}
              />
            </DetailCard>
          )}

          {query.status === 'EXECUTED' && (
            <DetailCard title={t('queries.detail.card_execution')} icon={<CheckOutlined className="u-fg-success" />}>
              <div
                className="u-grid u-gap-4 u-cols-3" style={{ padding: 14 }}
              >
                <Stat label={t('queries.detail.stat_rows')} value={fmtNum(query.rows_affected)} />
                <Stat
                  label={t('queries.detail.stat_duration')}
                  value={query.duration_ms != null ? `${query.duration_ms} ms` : '—'}
                />
                <Stat
                  label={t('queries.detail.stat_completed')}
                  value={timeAgo(query.updated_at)}
                />
              </div>
            </DetailCard>
          )}

          {query.status === 'FAILED' && (
            <DetailCard
              title={t('queries.detail.card_execution')}
              icon={<ExclamationCircleOutlined className="u-fg-danger" />}
            >
              <div className="u-text-sm" style={{ padding: 14, lineHeight: 1.55 }}>
                <div className="u-mb-3">
                  {t('queries.detail.execution_failed_body')}
                </div>
                <div
                  className="u-mb-3 u-mono u-text-xs u-bg-sunken u-border u-rounded-sm u-py-2 u-px-3"
                >
                  <div className="muted" style={{ marginBottom: 6 }}>
                    {t('queries.detail.execution_error_label')}
                  </div>
                  <div className="u-pre-wrap u-break-word">
                    {query.error_message?.trim()
                      ? query.error_message
                      : t('queries.detail.execution_no_error')}
                  </div>
                </div>
                <div
                  className="u-grid u-gap-4 u-cols-2"
                >
                  <Stat
                    label={t('queries.detail.stat_duration')}
                    value={query.duration_ms != null ? `${query.duration_ms} ms` : '—'}
                  />
                  <Stat
                    label={t('queries.detail.stat_completed')}
                    value={timeAgo(query.updated_at)}
                  />
                </div>
              </div>
            </DetailCard>
          )}

          {isRecurringParent && <OccurrencesCard queryId={query.id} />}

          {query.status === 'EXECUTED' && <QueryDiffCard query={query} />}

          {query.status === 'EXECUTED' && query.query_type === 'SELECT' && (
            <DetailCard title={t('queries.detail.card_results')} icon={<FileTextOutlined />}>
              <div style={{ padding: 14 }}>
                <QueryResultsTable
                  queryId={query.id}
                  defaultView={engineMode(query.db_type).defaultResultView}
                />
              </div>
            </DetailCard>
          )}

        </div>

        <div className="qd-side">
          {canDecide && (
            <div className="qd-decision">
              <div className="u-fw-600 u-text-sm u-mb-1">
                {t('queries.detail.review_required_title')}
              </div>
              <div className="muted u-text-xs u-mb-3">
                {t('queries.detail.review_required_subtitle')}
              </div>
              <Input.TextArea
                rows={3}
                placeholder={t('queries.detail.review_comment_placeholder')}
                value={comment}
                onChange={(e) => setComment(e.target.value)}
                className="u-mb-3"
              />
              <div className="qd-decision-actions">
                <Button
                  type="primary"
                  icon={<CheckOutlined />}
                  loading={approveMutation.isPending}
                  onClick={() => approveMutation.mutate()}
                >
                  {t('common.approve')}
                </Button>
                <Button
                  danger
                  icon={<CloseOutlined />}
                  loading={rejectMutation.isPending}
                  disabled={!comment.trim()}
                  title={
                    comment.trim()
                      ? undefined
                      : t('queries.detail.review_reject_required')
                  }
                  onClick={() => {
                    if (!comment.trim()) {
                      message.error(t('queries.detail.review_reject_required'));
                      return;
                    }
                    rejectMutation.mutate();
                  }}
                >
                  {t('common.reject')}
                </Button>
                <Button
                  icon={<EditOutlined />}
                  loading={requestChangesMutation.isPending}
                  disabled={!comment.trim()}
                  title={
                    comment.trim()
                      ? undefined
                      : t('queries.detail.review_request_changes_required')
                  }
                  onClick={() => {
                    if (!comment.trim()) {
                      message.error(t('queries.detail.review_request_changes_required'));
                      return;
                    }
                    requestChangesMutation.mutate();
                  }}
                >
                  {t('queries.detail.review_request_changes')}
                </Button>
              </div>
            </div>
          )}
          <ApprovalTimeline stages={stages} />
          {contextNotices.length > 0 && (
            <DetailCard title={t('queries.detail.handling_title')} icon={<InfoCircleOutlined />}>
              <ul className="qd-notices">
                {contextNotices.map((n) => (
                  <li key={n.key} className="qd-notice" data-testid={n.testId}>
                    <div className="qd-notice-title">{n.title}</div>
                    {n.description && <div className="qd-notice-body">{n.description}</div>}
                    {n.action && <div className="qd-notice-action">{n.action}</div>}
                  </li>
                ))}
              </ul>
            </DetailCard>
          )}
          {query.linked_tickets?.length > 0 && <LinkedTickets tickets={query.linked_tickets} />}
          <Metadata query={query} />
        </div>
      </div>
    </Page>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div
        className="muted mono u-text-2xs u-mb-1 u-uppercase u-tracking-wide"
      >
        {label}
      </div>
      <div className="u-fw-600 u-mono" style={{ fontSize: 18 }}>{value}</div>
    </div>
  );
}

function LinkedTickets({ tickets }: { tickets: LinkedTicketRef[] }) {
  const { t } = useTranslation();
  return (
    <div
      className="af-box u-p-4"
    >
      <div className="u-fw-600 u-text-sm u-mb-3">
        {t('queries.detail.linked_tickets_title')}
      </div>
      <div className="u-flex u-col" style={{ gap: 10 }}>
        {tickets.map((ticket) => (
          <div key={ticket.id} className="u-text-xs u-mono">
            <div className="u-flex u-justify-between u-gap-2">
              {ticket.url ? (
                <a href={ticket.url} target="_blank" rel="noopener noreferrer">
                  {ticket.external_key}
                </a>
              ) : (
                <span>{ticket.external_key}</span>
              )}
              <span className="muted">{channelTypeLabel(t, ticket.system)}</span>
            </div>
            <div className="muted" style={{ marginTop: 2 }}>
              {t('queries.detail.linked_tickets_status', {
                status: ticket.resolution
                  ? `${ticket.status} (${ticket.resolution})`
                  : ticket.status,
              })}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function Metadata({ query }: { query: QueryDetail }) {
  const { t } = useTranslation();
  return (
    <div
      className="af-box u-p-4"
    >
      <div className="u-fw-600 u-text-sm u-mb-3">
        {t('queries.detail.metadata_title')}
      </div>
      <div
        className="u-flex u-col u-gap-2 u-text-xs u-mono"
      >
        <Row k="query.id" v={query.id} />
        <Row k="query.type" v={query.query_type} />
        <Row k="datasource" v={query.datasource.name} />
        {query.review_plan_name && <Row k="plan" v={query.review_plan_name} />}
        {query.approval_timeout_hours != null && (
          <Row k="timeout.hours" v={String(query.approval_timeout_hours)} />
        )}
        <Row k="created" v={fmtDate(query.created_at)} />
        <Row k="updated" v={fmtDate(query.updated_at)} />
        {query.scheduled_for && (
          <Row k={t('queries.detail.scheduled_for_label')} v={fmtDate(query.scheduled_for)} />
        )}
        {query.recurrence_rule && (
          <>
            <Row k={t('queries.detail.recurrence_rule_label')} v={query.recurrence_rule} />
            {query.recurrence_until && (
              <Row
                k={t('queries.detail.recurrence_until_label')}
                v={fmtDate(query.recurrence_until)}
              />
            )}
            {query.recurrence_next_run_at && (
              <Row
                k={t('queries.detail.recurrence_next_run_label')}
                v={fmtDate(query.recurrence_next_run_at)}
              />
            )}
          </>
        )}
        {query.rows_affected != null && <Row k="rows" v={fmtNum(query.rows_affected)} />}
        {query.duration_ms != null && <Row k="exec.ms" v={String(query.duration_ms)} />}
      </div>
    </div>
  );
}

/** Paginated history of a recurring series' occurrence rows (#627), newest first. */
function OccurrencesCard({ queryId }: { queryId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const size = 10;
  const { data, isLoading } = useQuery({
    queryKey: queryKeys.occurrences(queryId, page, size),
    queryFn: () => listOccurrences(queryId, page, size),
  });
  return (
    <DetailCard title={t('queries.detail.card_occurrences')} icon={<SyncOutlined />}>
      <div style={{ padding: 14 }} data-testid="occurrences-card">
        {isLoading ? (
          <Skeleton active paragraph={{ rows: 3 }} />
        ) : (
          <DataTable<QueryOccurrence>
            size="small"
            rowKey="id"
            dataSource={data?.items ?? []}
            emptyTitle={t('queries.detail.occurrences_empty')}
            pagination={{
              current: page + 1,
              pageSize: size,
              total: data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
            columns={[
              {
                title: t('queries.detail.occurrences_col_id'),
                dataIndex: 'id',
                render: (id: string) => (
                  <Button
                    type="link"
                    size="small"
                    className="mono u-p-0"
                    onClick={() => navigate(`/queries/${id}`)}
                  >
                    {id.slice(0, 8)}
                  </Button>
                ),
              },
              {
                title: t('queries.detail.occurrences_col_status'),
                dataIndex: 'status',
                render: (status: QueryOccurrence['status']) => <StatusPill status={status} />,
              },
              {
                title: t('queries.detail.occurrences_col_rows'),
                dataIndex: 'rows_affected',
                align: 'right',
                render: (rows: number | null) => (rows != null ? fmtNum(rows) : '—'),
              },
              {
                title: t('queries.detail.occurrences_col_duration'),
                dataIndex: 'execution_duration_ms',
                align: 'right',
                render: (ms: number | null) => (ms != null ? `${ms} ms` : '—'),
              },
              {
                title: t('queries.detail.occurrences_col_executed_at'),
                dataIndex: 'executed_at',
                render: (at: string | null) => (at ? fmtDate(at) : '—'),
              },
              {
                title: t('queries.detail.occurrences_col_error'),
                dataIndex: 'error_message',
                ellipsis: true,
                render: (err: string | null) => err ?? '—',
              },
            ]}
          />
        )}
      </div>
    </DetailCard>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="u-flex u-justify-between u-gap-2">
      <span className="muted">{k}</span>
      <span
        className="u-text-right u-min-w-0 u-nowrap u-overflow-hidden u-ellipsis"
      >
        {v}
      </span>
    </div>
  );
}
