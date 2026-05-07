import { useMemo, useState } from 'react';
import { App, Button, Input, Skeleton } from 'antd';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  CloseOutlined,
  CopyOutlined,
  EditOutlined,
  FileTextOutlined,
  InfoCircleOutlined,
  PlayCircleOutlined,
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
import { fmtDate, fmtNum, timeAgo } from '@/utils/dateFormat';
import {
  approveQuery,
  cancelQuery,
  executeQuery,
  getQuery,
  isPending,
  queryKeys,
  rejectQuery,
} from '@/api/queries';
import type { QueryDetail } from '@/types/api';

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
    refetchInterval: (q) => (q.state.data && isPending(q.state.data.status) ? 5000 : false),
  });

  const cancelMutation = useMutation({
    mutationFn: () => cancelQuery(id!),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.detail(id!) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      message.info(t('queries.detail.on_cancel_success'));
    },
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

  const approveMutation = useMutation({
    mutationFn: () => approveQuery(id!, comment),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.detail(id!) });
      message.success(t('queries.detail.on_approve_success'));
    },
  });

  const rejectMutation = useMutation({
    mutationFn: () => rejectQuery(id!, comment),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.detail(id!) });
      message.error(t('queries.detail.on_reject_success'));
    },
  });

  const stages: TimelineStage[] = useMemo(() => {
    if (!query) return [];
    return buildStages(query);
  }, [query]);

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
  const canCancel =
    submitterId === user.id &&
    (query.status === 'PENDING_AI' || query.status === 'PENDING_REVIEW');
  const canExecute =
    query.status === 'APPROVED' && (submitterId === user.id || user.role === 'ADMIN');

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
              <Button
                danger
                icon={<CloseOutlined />}
                loading={cancelMutation.isPending}
                onClick={() => cancelMutation.mutate()}
              >
                {t('queries.detail.cancel_query')}
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
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: 28,
          display: 'grid',
          gridTemplateColumns: '1fr 360px',
          gap: 24,
          alignContent: 'start',
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <Card title={t('queries.detail.card_sql')} icon={<FileTextOutlined />} extra={<QueryTypePill type={query.query_type} />}>
            <div style={{ padding: 14 }}>
              <SqlBlock sql={query.sql_text} />
            </div>
          </Card>

          <Card title={t('queries.detail.card_justification')} icon={<InfoCircleOutlined />}>
            <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>{query.justification}</div>
          </Card>

          <Card
            title={t('queries.detail.card_ai')}
            icon={<ThunderboltOutlined style={{ color: 'var(--accent)' }} />}
            extra={
              query.ai_analysis ? (
                <>
                  <RiskPill
                    level={query.ai_analysis.risk_level}
                    score={query.ai_analysis.risk_score}
                  />
                  <span className="mono muted" style={{ marginLeft: 'auto', fontSize: 11 }}>
                    {query.ai_analysis.ai_provider.toLowerCase()} · {query.ai_analysis.ai_model}
                  </span>
                </>
              ) : null
            }
          >
            <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
              {query.ai_analysis?.summary ?? (
                <span className="muted">{t('queries.detail.ai_awaiting')}</span>
              )}
            </div>
            {query.ai_analysis && query.ai_analysis.issues.length > 0 && (
              <div
                style={{ padding: '0 14px 14px', display: 'flex', flexDirection: 'column', gap: 8 }}
              >
                {query.ai_analysis.issues.map((iss, i) => (
                  <IssueCard key={i} issue={iss} />
                ))}
              </div>
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

          {query.status === 'EXECUTED' && query.query_type === 'SELECT' && (
            <Card title={t('queries.detail.card_results')} icon={<FileTextOutlined />}>
              <div style={{ padding: 14 }}>
                <QueryResultsTable queryId={query.id} />
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
                  onClick={() => rejectMutation.mutate()}
                >
                  {t('common.reject')}
                </Button>
                <Button icon={<EditOutlined />}>{t('queries.detail.review_request_changes')}</Button>
              </div>
            </div>
          )}
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <ApprovalTimeline stages={stages} />
          <Metadata query={query} />
        </div>
      </div>
    </div>
  );
}

function buildStages(query: QueryDetail): TimelineStage[] {
  const out: TimelineStage[] = [
    {
      label: 'Submitted',
      who: query.submitted_by.display_name,
      time: query.created_at,
      done: true,
    },
  ];
  out.push({
    label: 'AI analysis',
    who: query.ai_analysis
      ? `${query.ai_analysis.ai_provider.toLowerCase()} / ${query.ai_analysis.ai_model}`
      : 'pending',
    time: query.ai_analysis ? query.created_at : null,
    done: ['PENDING_REVIEW', 'APPROVED', 'EXECUTED', 'REJECTED', 'FAILED'].includes(query.status),
    active: query.status === 'PENDING_AI',
    detail: query.ai_analysis
      ? `${query.ai_analysis.risk_level} · score ${query.ai_analysis.risk_score}`
      : 'analyzing…',
  });
  if (query.status !== 'APPROVED' || query.duration_ms == null) {
    const reviewDone = ['APPROVED', 'EXECUTED', 'REJECTED'].includes(query.status);
    out.push({
      label: query.status === 'REJECTED' ? 'Rejected' : 'Human review',
      who: reviewDone ? '—' : 'awaiting reviewer',
      time: reviewDone ? query.updated_at : null,
      done: reviewDone,
      active: query.status === 'PENDING_REVIEW',
      rejected: query.status === 'REJECTED',
      detail: null,
    });
  }
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
        : null,
  });
  return out;
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
        <Row k="created" v={fmtDate(query.created_at)} />
        <Row k="updated" v={fmtDate(query.updated_at)} />
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
