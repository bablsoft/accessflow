import { App, Button, Tabs } from 'antd';
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { RiskPill } from '@/components/common/RiskPill';
import { Avatar } from '@/components/common/Avatar';
import { SqlBlock } from '@/components/common/SqlBlock';
import { useQueriesStore } from '@/store/queriesStore';
import { useAuthStore } from '@/store/authStore';
import { timeAgo } from '@/utils/dateFormat';
import { approveQuery, rejectQuery } from '@/api/queries';
import type { QueryRequest } from '@/types/api';

export function ReviewQueuePage() {
  const queries = useQueriesStore((s) => s.queries);
  const user = useAuthStore((s) => s.user());
  const [tab, setTab] = useState('mine');
  const { message } = App.useApp();
  const navigate = useNavigate();

  const pending = queries.filter(
    (q) => q.status === 'PENDING_REVIEW' && q.submitted_by !== user?.id,
  );
  const list = tab === 'mine' ? pending.slice(0, 8) : pending;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title="Review queue"
        subtitle="Queries awaiting your approval. Newest first."
        actions={<Button icon={<ReloadOutlined />}>Refresh</Button>}
      />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        style={{ padding: '0 28px' }}
        items={[
          { key: 'mine', label: `Assigned to you · ${Math.min(8, pending.length)}` },
          { key: 'all', label: `All pending · ${pending.length}` },
          { key: 'recent', label: 'Recently decided' },
        ]}
      />
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: 24,
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(420px, 1fr))',
          gap: 16,
          alignContent: 'start',
        }}
      >
        {list.map((q) => (
          <ReviewCard
            key={q.id}
            query={q}
            onOpen={() => navigate(`/queries/${q.id}`)}
            onApprove={async () => {
              await approveQuery(q.id);
              message.success('Approved · forwarded to execution');
            }}
            onReject={async () => {
              await rejectQuery(q.id);
              message.error('Rejected · submitter notified');
            }}
          />
        ))}
        {list.length === 0 && (
          <EmptyState
            title="All caught up"
            description="No queries are waiting on you right now."
          />
        )}
      </div>
    </div>
  );
}

interface CardProps {
  query: QueryRequest;
  onOpen: () => void;
  onApprove: () => void;
  onReject: () => void;
}

function ReviewCard({ query, onOpen, onApprove, onReject }: CardProps) {
  return (
    <div
      onClick={onOpen}
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 14,
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        cursor: 'pointer',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span className="mono muted" style={{ fontSize: 11 }}>
          {query.id}
        </span>
        <QueryTypePill type={query.query_type} size="sm" />
        <RiskPill level={query.risk_level} score={query.risk_score} size="sm" />
        <div style={{ flex: 1 }} />
        <span className="muted" style={{ fontSize: 11 }}>
          {timeAgo(query.created_at)}
        </span>
      </div>
      <SqlBlock
        sql={query.sql.length > 200 ? query.sql.slice(0, 200) + '…' : query.sql}
        style={{ fontSize: 11.5, padding: 10, maxHeight: 100, overflow: 'hidden' }}
      />
      <div className="muted" style={{ fontSize: 12, lineHeight: 1.45 }}>
        <strong style={{ color: 'var(--fg)' }}>Why:</strong> {query.justification.slice(0, 110)}
        {query.justification.length > 110 ? '…' : ''}
      </div>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          paddingTop: 10,
          borderTop: '1px solid var(--border)',
        }}
      >
        <Avatar name={query.submitter_name} size={22} />
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 12, fontWeight: 500 }}>{query.submitter_name}</div>
          <div className="mono muted" style={{ fontSize: 10 }}>
            {query.datasource_name}
          </div>
        </div>
        <div style={{ flex: 1 }} />
        <Button
          size="small"
          danger
          icon={<CloseOutlined />}
          onClick={(e) => {
            e.stopPropagation();
            onReject();
          }}
        >
          Reject
        </Button>
        <Button
          size="small"
          type="primary"
          icon={<CheckOutlined />}
          onClick={(e) => {
            e.stopPropagation();
            onApprove();
          }}
        >
          Approve
        </Button>
      </div>
    </div>
  );
}
