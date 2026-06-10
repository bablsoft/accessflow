import { useMemo, useState } from 'react';
import { Alert, App, Button, Input, Popconfirm, Skeleton } from 'antd';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  ClockCircleOutlined,
  CloseOutlined,
  CopyOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  FileTextOutlined,
  InfoCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { SqlBlock } from '@/components/common/SqlBlock';
import { ApprovalTimeline, type TimelineStage } from '@/components/review/ApprovalTimeline';
import { IssueCard } from '@/components/editor/IssueCard';
import { QueryResultsTable } from '@/components/queries/QueryResultsTable';
import { useAuthStore } from '@/store/authStore';
import { routingActionLabel } from '@/utils/enumLabels';
import { fmtDate, fmtNum, timeAgo } from '@/utils/dateFormat';
import { engineMode } from '@/utils/engineModes';
import { cancelQuery, executeQuery, getQuery, queryKeys, reanalyzeQuery } from '@/api/queries';
import {
  approveQuery,
  rejectQuery,
  requestChanges,
  reviewKeys,
} from '@/api/reviews';
import { queryCancelErrorMessage, reviewErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { userDisplay } from '@/utils/userDisplay';
import type { QueryDetail } from '@/types/api';
import { QueryDiffCard } from './QueryDiffCard';
import { buildTimelineStages } from './buildTimelineStages';
import './query-detail.css';

export function QueryDetailPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const { message } = App.useApp();
  const [comment, setComment] = useState('');
  const queryClient = useQueryClient();

  const { data: query, isLoading } = useQuery({
    queryKey: queryKeys.detail(id ?? ''),
    queryFn: () => getQuery(id!),
    enabled: !!id,
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
      if (data.status === 'EXECUTED') {
        message.success(t('queries.detail.on_execute_success'));
      } else {
        message.error(t('queries.detail.on_execute_error'));
      }
    },
  });

  const reanalyzeMutation = useMutation({
    mutationFn: () => reanalyzeQuery(id!),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.detail(id!) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      message.success(t('queries.detail.ai_failed_reanalyze_success'));
    },
    onError: () => {
      message.error(t('queries.detail.ai_failed_reanalyze_error'));
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
      message.error(t('queries.detail.on_reject_success'));
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
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 8 }} />
      </div>
    );
  }
  if (!query || !user) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div className="muted">{t('queries.detail.not_found_message')}</div>
        <Button onClick={() => navigate('/queries')} style={{ marginTop: 12 }}>
          {t('queries.detail.back_to_history')}
        </Button>
      </div>
    );
  }

  const isReviewer = user.role === 'REVIEWER' || user.role === 'ADMIN';
  const submitterId = query.submitted_by.id;
  const canDecide = isReviewer && query.status === 'PENDING_REVIEW' && submitterId !== user.id;
  const hasScheduledRun =
    query.status === 'APPROVED' && !!query.scheduled_for;
  const showScheduledBanner =
    !!query.scheduled_for &&
    (query.status === 'PENDING_AI' ||
      query.status === 'PENDING_REVIEW' ||
      query.status === 'APPROVED');
  const canCancel =
    submitterId === user.id &&
    (query.status === 'PENDING_AI' ||
      query.status === 'PENDING_REVIEW' ||
      hasScheduledRun);
  const canExecute =
    query.status === 'APPROVED' && (submitterId === user.id || user.role === 'ADMIN');
  const aiFailed = query.ai_analysis?.failed === true;
  const aiFailureReason = query.ai_analysis?.error_message ?? '';
  const canReanalyze = isReviewer && aiFailed && query.status === 'PENDING_REVIEW';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        breadcrumbs={[t('queries.detail.breadcrumb_queries'), query.id]}
        title={
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 12 }}>
            <span className="mono">{query.id}</span>
            <StatusPill status={query.status} />
          </span>
        }
        subtitle={
          <>
            {t('queries.detail.submitted_by')} <strong>{query.submitted_by.display_name}</strong>{' '}
            · {fmtDate(query.created_at)} ·{' '}
            <span className="mono">{query.datasource.name}</span>
          </>
        }
        actions={
          <>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/queries')}>
              {t('common.back')}
            </Button>
            <Button icon={<CopyOutlined />}>{t('common.duplicate')}</Button>
            {canCancel && (
              <Popconfirm
                title={
                  hasScheduledRun
                    ? t('queries.detail.cancel_schedule_confirm_title')
                    : t('queries.detail.cancel_confirm_title')
                }
                description={
                  hasScheduledRun
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
                  {hasScheduledRun
                    ? t('queries.detail.cancel_schedule')
                    : t('queries.detail.cancel_query')}
                </Button>
              </Popconfirm>
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
      <div className="qd-grid">
        <div className="qd-main">
          {showScheduledBanner && (
            <Alert
              type="info"
              showIcon
              icon={<ClockCircleOutlined />}
              message={
                hasScheduledRun
                  ? t('queries.detail.scheduled_banner_title')
                  : t('queries.detail.scheduled_banner_title_pending')
              }
              description={
                hasScheduledRun
                  ? t('queries.detail.scheduled_banner_body', {
                      when: fmtDate(query.scheduled_for!),
                    })
                  : t('queries.detail.scheduled_banner_body_pending', {
                      when: fmtDate(query.scheduled_for!),
                    })
              }
            />
          )}
          {changesRequested && latestDecision && (
            <Alert
              type="info"
              showIcon
              icon={<EditOutlined />}
              message={t('queries.detail.changes_requested_banner_title')}
              description={t('queries.detail.changes_requested_banner_body', {
                reviewer: userDisplay(
                  latestDecision.reviewer.display_name,
                  latestDecision.reviewer.email,
                ),
                when: timeAgo(latestDecision.decided_at),
                comment: latestDecision.comment ?? '',
              })}
            />
          )}
          {aiFailed && (
            <Alert
              type="warning"
              showIcon
              icon={<ExclamationCircleOutlined />}
              message={t('queries.detail.ai_failed_banner_title')}
              description={t('queries.detail.ai_failed_banner_detail', {
                reason: aiFailureReason || '—',
              })}
              action={
                canReanalyze ? (
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
                ) : undefined
              }
            />
          )}
          {query.status === 'TIMED_OUT' && (
            <Alert
              type="warning"
              showIcon
              icon={<ClockCircleOutlined />}
              message={t('queries.detail.timeout_title')}
              description={
                query.review_plan_name
                  ? t('queries.detail.timeout_body_with_plan', {
                      plan: query.review_plan_name,
                      hours: query.approval_timeout_hours ?? '?',
                      when: timeAgo(query.updated_at),
                    })
                  : t('queries.detail.timeout_body_without_plan', {
                      hours: query.approval_timeout_hours ?? '?',
                      when: timeAgo(query.updated_at),
                    })
              }
            />
          )}
          {query.matched_policy && (
            <Alert
              type="info"
              showIcon
              message={t('queries.detail.matched_policy_title', {
                action: routingActionLabel(t, query.matched_policy.action),
              })}
              description={t('queries.detail.matched_policy_body', {
                name:
                  query.matched_policy.policy_name ??
                  t('queries.detail.matched_policy_deleted'),
                reason:
                  query.matched_policy.reason ?? t('queries.detail.matched_policy_no_reason'),
              })}
            />
          )}
          <Card title={t('queries.detail.card_sql')} icon={<FileTextOutlined />} extra={<QueryTypePill type={query.query_type} />}>
            <div style={{ padding: 14 }}>
              <SqlBlock sql={query.sql_text} />
            </div>
          </Card>

          <Card title={t('queries.detail.card_justification')} icon={<InfoCircleOutlined />}>
            <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
              {query.justification?.trim() ? (
                query.justification
              ) : (
                <span className="muted" style={{ fontStyle: 'italic' }}>
                  {t('queries.detail.no_justification')}
                </span>
              )}
            </div>
          </Card>

          <Card
            title={
              aiFailed
                ? t('queries.detail.ai_failed_accordion_title')
                : aiSkipped
                  ? t('queries.detail.card_ai_skipped')
                  : t('queries.detail.card_ai')
            }
            icon={<ThunderboltOutlined style={{ color: 'var(--accent)' }} />}
            extra={
              query.ai_analysis ? (
                <>
                  <RiskPill
                    level={query.ai_analysis.risk_level}
                    score={query.ai_analysis.risk_score}
                    failed={aiFailed}
                  />
                  {!aiFailed && (
                    <span className="mono muted" style={{ marginLeft: 'auto', fontSize: 11 }}>
                      {query.ai_analysis.ai_provider.toLowerCase()} · {query.ai_analysis.ai_model}
                    </span>
                  )}
                </>
              ) : null
            }
          >
            {aiFailed ? (
              <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
                <div style={{ marginBottom: 12 }}>
                  {t('queries.detail.ai_failed_accordion_body')}
                </div>
                <div
                  style={{
                    background: 'var(--bg-sunken)',
                    border: '1px solid var(--border)',
                    borderRadius: 'var(--radius-sm)',
                    padding: '8px 12px',
                    marginBottom: 12,
                    fontFamily: 'var(--font-mono)',
                    fontSize: 12,
                  }}
                >
                  <span className="muted" style={{ marginRight: 8 }}>
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
                <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
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
                    style={{
                      padding: '0 14px 14px',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: 8,
                    }}
                  >
                    {query.ai_analysis.issues.map((iss, i) => (
                      <IssueCard key={i} issue={iss} />
                    ))}
                  </div>
                )}
              </>
            )}
          </Card>

          {query.status === 'EXECUTED' && (
            <Card title={t('queries.detail.card_execution')} icon={<CheckOutlined style={{ color: 'var(--risk-low)' }} />}>
              <div
                style={{
                  padding: 14,
                  display: 'grid',
                  gridTemplateColumns: 'repeat(3, 1fr)',
                  gap: 16,
                }}
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
            </Card>
          )}

          {query.status === 'FAILED' && (
            <Card
              title={t('queries.detail.card_execution')}
              icon={<ExclamationCircleOutlined style={{ color: 'var(--risk-crit)' }} />}
            >
              <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
                <div style={{ marginBottom: 12 }}>
                  {t('queries.detail.execution_failed_body')}
                </div>
                <div
                  style={{
                    background: 'var(--bg-sunken)',
                    border: '1px solid var(--border)',
                    borderRadius: 'var(--radius-sm)',
                    padding: '8px 12px',
                    marginBottom: 12,
                    fontFamily: 'var(--font-mono)',
                    fontSize: 12,
                  }}
                >
                  <div className="muted" style={{ marginBottom: 6 }}>
                    {t('queries.detail.execution_error_label')}
                  </div>
                  <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                    {query.error_message?.trim()
                      ? query.error_message
                      : t('queries.detail.execution_no_error')}
                  </div>
                </div>
                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(2, 1fr)',
                    gap: 16,
                  }}
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
            </Card>
          )}

          {query.status === 'EXECUTED' && <QueryDiffCard query={query} />}

          {query.status === 'EXECUTED' && query.query_type === 'SELECT' && (
            <Card title={t('queries.detail.card_results')} icon={<FileTextOutlined />}>
              <div style={{ padding: 14 }}>
                <QueryResultsTable
                  queryId={query.id}
                  defaultView={engineMode(query.db_type).defaultResultView}
                />
              </div>
            </Card>
          )}

          {canDecide && (
            <div
              style={{
                background: 'var(--bg-elev)',
                border: '1px solid var(--accent-border)',
                borderRadius: 'var(--radius-md)',
                padding: 16,
              }}
            >
              <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 4 }}>
                {t('queries.detail.review_required_title')}
              </div>
              <div className="muted" style={{ fontSize: 12, marginBottom: 12 }}>
                {t('queries.detail.review_required_subtitle')}
              </div>
              <Input.TextArea
                rows={3}
                placeholder={t('queries.detail.review_comment_placeholder')}
                value={comment}
                onChange={(e) => setComment(e.target.value)}
                style={{ marginBottom: 12 }}
              />
              <div style={{ display: 'flex', gap: 8 }}>
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
        </div>

        <div className="qd-side">
          <ApprovalTimeline stages={stages} />
          <Metadata query={query} />
        </div>
      </div>
    </div>
  );
}

function Card({
  title, icon, extra, children,
}: { title: string; icon?: React.ReactNode; extra?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
      }}
    >
      <div
        style={{
          padding: '10px 14px',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        {icon && <span style={{ color: 'var(--fg-muted)' }}>{icon}</span>}
        <span style={{ fontWeight: 600, fontSize: 13 }}>{title}</span>
        {extra}
      </div>
      {children}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div
        className="muted mono"
        style={{
          fontSize: 10,
          textTransform: 'uppercase',
          letterSpacing: '0.04em',
          marginBottom: 4,
        }}
      >
        {label}
      </div>
      <div style={{ fontSize: 18, fontWeight: 600, fontFamily: 'var(--font-mono)' }}>{value}</div>
    </div>
  );
}

function Metadata({ query }: { query: QueryDetail }) {
  const { t } = useTranslation();
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 16,
      }}
    >
      <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 12 }}>
        {t('queries.detail.metadata_title')}
      </div>
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          fontSize: 12,
          fontFamily: 'var(--font-mono)',
        }}
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
        {query.rows_affected != null && <Row k="rows" v={fmtNum(query.rows_affected)} />}
        {query.duration_ms != null && <Row k="exec.ms" v={String(query.duration_ms)} />}
      </div>
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
      <span className="muted">{k}</span>
      <span
        style={{
          textAlign: 'right',
          minWidth: 0,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {v}
      </span>
    </div>
  );
}
