import { useMemo, useState } from 'react';
import { App, Button, Input } from 'antd';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  CloseOutlined,
  CopyOutlined,
  EditOutlined,
  FileTextOutlined,
  InfoCircleOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { SqlBlock } from '@/components/common/SqlBlock';
import { ApprovalTimeline, type TimelineStage } from '@/components/review/ApprovalTimeline';
import { IssueCard } from '@/components/editor/IssueCard';
import { useQueriesStore } from '@/store/queriesStore';
import { useAuthStore } from '@/store/authStore';
import { DATASOURCES, REVIEW_PLANS } from '@/mocks/data';
import { fmtDate, fmtNum, timeAgo } from '@/utils/dateFormat';
import { approveQuery, cancelQuery, rejectQuery } from '@/api/queries';

export function QueryDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const query = useQueriesStore((s) => s.queries.find((q) => q.id === id));
  const user = useAuthStore((s) => s.user());
  const { message } = App.useApp();
  const [comment, setComment] = useState('');

  const ds = query ? DATASOURCES.find((d) => d.id === query.datasource_id) : null;
  const plan = ds ? REVIEW_PLANS.find((p) => p.id === ds.plan)! : null;

  const stages: TimelineStage[] = useMemo(() => {
    if (!query || !plan || !ds) return [];
    const out: TimelineStage[] = [
      { label: 'Submitted', who: query.submitter_name, time: query.created_at, done: true },
    ];
    out.push({
      label: 'AI analysis',
      who: 'anthropic / claude-sonnet-4',
      time: query.created_at,
      done: ['PENDING_REVIEW', 'APPROVED', 'EXECUTED', 'REJECTED', 'FAILED'].includes(query.status),
      active: query.status === 'PENDING_AI',
      detail:
        query.status !== 'PENDING_AI'
          ? `${query.risk_level} · score ${query.risk_score}`
          : 'analyzing…',
    });
    if (plan.requires_human) {
      const reviewDone = ['APPROVED', 'EXECUTED', 'REJECTED'].includes(query.status);
      out.push({
        label: query.status === 'REJECTED' ? 'Rejected' : 'Human review',
        who: reviewDone ? 'Marcus Holt' : 'awaiting reviewer',
        time: reviewDone ? query.created_at : null,
        done: reviewDone,
        active: query.status === 'PENDING_REVIEW',
        rejected: query.status === 'REJECTED',
        detail: query.status === 'REJECTED'
          ? '"Please add a more specific WHERE clause."'
          : reviewDone ? '"Looks good — bounded scope, indexed WHERE."' : null,
      });
    }
    out.push({
      label: query.status === 'FAILED' ? 'Execution failed' : query.status === 'CANCELLED' ? 'Cancelled' : 'Execute',
      who: query.status === 'EXECUTED' ? `proxy → ${ds.name}` : '—',
      time: query.status === 'EXECUTED' ? query.created_at : null,
      done: query.status === 'EXECUTED',
      failed: query.status === 'FAILED',
      cancelled: query.status === 'CANCELLED',
      detail:
        query.status === 'EXECUTED'
          ? `${fmtNum(query.rows_affected)} rows · ${query.duration_ms}ms`
          : null,
    });
    return out;
  }, [query, plan, ds]);

  if (!query || !ds || !plan || !user) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div className="muted">Query not found.</div>
        <Button onClick={() => navigate('/queries')} style={{ marginTop: 12 }}>
          Back to history
        </Button>
      </div>
    );
  }

  const isReviewer = user.role === 'REVIEWER' || user.role === 'ADMIN';
  // Self-approval guard
  const canDecide =
    isReviewer && query.status === 'PENDING_REVIEW' && query.submitted_by !== user.id;
  const canCancel =
    query.submitted_by === user.id &&
    (query.status === 'PENDING_AI' || query.status === 'PENDING_REVIEW');

  const onApprove = async () => {
    await approveQuery(query.id, comment);
    message.success('Approved · forwarded to execution');
  };
  const onReject = async () => {
    await rejectQuery(query.id, comment);
    message.error('Rejected · submitter notified');
  };
  const onCancel = async () => {
    await cancelQuery(query.id);
    message.info('Query cancelled');
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        breadcrumbs={['Queries', query.id]}
        title={
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 12 }}>
            <span className="mono">{query.id}</span>
            <StatusPill status={query.status} />
          </span>
        }
        subtitle={
          <>
            Submitted by <strong>{query.submitter_name}</strong> · {fmtDate(query.created_at)} ·{' '}
            <span className="mono">{query.datasource_name}</span>
          </>
        }
        actions={
          <>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/queries')}>
              Back
            </Button>
            <Button icon={<CopyOutlined />}>Duplicate</Button>
            {canCancel && (
              <Button danger icon={<CloseOutlined />} onClick={onCancel}>
                Cancel query
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
          <Card title="SQL" icon={<FileTextOutlined />} extra={<QueryTypePill type={query.query_type} />}>
            <div style={{ padding: 14 }}>
              <SqlBlock sql={query.sql} />
            </div>
          </Card>

          <Card title="Justification" icon={<InfoCircleOutlined />}>
            <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>{query.justification}</div>
          </Card>

          <Card
            title="AI analysis"
            icon={<ThunderboltOutlined style={{ color: 'var(--accent)' }} />}
            extra={
              <>
                <RiskPill level={query.risk_level} score={query.risk_score} />
                <span className="mono muted" style={{ marginLeft: 'auto', fontSize: 11 }}>
                  anthropic · claude-sonnet-4
                </span>
              </>
            }
          >
            <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
              {query.ai_summary || <span className="muted">Awaiting analysis…</span>}
            </div>
            {query.ai_issues.length > 0 && (
              <div
                style={{ padding: '0 14px 14px', display: 'flex', flexDirection: 'column', gap: 8 }}
              >
                {query.ai_issues.map((iss, i) => <IssueCard key={i} issue={iss} />)}
              </div>
            )}
          </Card>

          {query.status === 'EXECUTED' && (
            <Card title="Execution result" icon={<CheckOutlined style={{ color: 'var(--risk-low)' }} />}>
              <div
                style={{
                  padding: 14,
                  display: 'grid',
                  gridTemplateColumns: 'repeat(3, 1fr)',
                  gap: 16,
                }}
              >
                <Stat label="rows affected" value={fmtNum(query.rows_affected)} />
                <Stat label="duration" value={`${query.duration_ms} ms`} />
                <Stat label="completed" value={timeAgo(query.created_at)} />
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
                Your review is required
              </div>
              <div className="muted" style={{ fontSize: 12, marginBottom: 12 }}>
                Approve to forward to execution, reject to send back to submitter.
              </div>
              <Input.TextArea
                rows={3}
                placeholder="Optional comment for the submitter…"
                value={comment}
                onChange={(e) => setComment(e.target.value)}
                style={{ marginBottom: 12 }}
              />
              <div style={{ display: 'flex', gap: 8 }}>
                <Button type="primary" icon={<CheckOutlined />} onClick={onApprove}>
                  Approve
                </Button>
                <Button danger icon={<CloseOutlined />} onClick={onReject}>
                  Reject
                </Button>
                <Button icon={<EditOutlined />}>Request changes</Button>
              </div>
            </div>
          )}
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <ApprovalTimeline stages={stages} />
          <Metadata query={query} ds={ds} planName={plan.name} />
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

function Metadata({
  query, ds, planName,
}: { query: import('@/types/api').QueryRequest; ds: { name: string; db_type: string }; planName: string }) {
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 16,
      }}
    >
      <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 12 }}>Metadata</div>
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
        <Row k="datasource" v={ds.name} />
        <Row k="db_type" v={ds.db_type} />
        <Row k="review_plan" v={planName} />
        <Row k="created" v={fmtDate(query.created_at)} />
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
