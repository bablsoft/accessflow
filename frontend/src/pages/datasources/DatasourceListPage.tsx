import { useMemo, useState } from 'react';
import { Button, Input } from 'antd';
import { DatabaseOutlined, PlusOutlined, SearchOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '@/components/common/PageHeader';
import { Pill } from '@/components/common/Pill';
import { DATASOURCES, PERMS, REVIEW_PLANS } from '@/mocks/data';
import { useQueriesStore } from '@/store/queriesStore';
import type { Datasource } from '@/types/api';

export function DatasourceListPage() {
  const [q, setQ] = useState('');
  const queries = useQueriesStore((s) => s.queries);
  const navigate = useNavigate();
  const filtered = useMemo(
    () => DATASOURCES.filter((d) => !q || d.name.toLowerCase().includes(q.toLowerCase())),
    [q],
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title="Datasources"
        subtitle="Connected customer databases proxied through AccessFlow."
        actions={<Button type="primary" icon={<PlusOutlined />}>Add datasource</Button>}
      />
      <div
        style={{
          padding: '12px 28px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-elev)',
          display: 'flex',
          gap: 8,
        }}
      >
        <Input
          prefix={<SearchOutlined style={{ color: 'var(--fg-faint)' }} />}
          placeholder="Search datasources…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ width: 280 }}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11, alignSelf: 'center' }}>
          {filtered.length} of {DATASOURCES.length}
        </span>
      </div>
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: 24,
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))',
          gap: 14,
          alignContent: 'start',
        }}
      >
        {filtered.map((ds) => (
          <DsCard
            key={ds.id}
            ds={ds}
            queries={queries.filter((q) => q.datasource_id === ds.id).length}
            users={PERMS.filter((p) => p.datasource_id === ds.id).length}
            onOpen={() => navigate(`/datasources/${ds.id}/settings`)}
          />
        ))}
      </div>
    </div>
  );
}

interface CardProps {
  ds: Datasource;
  queries: number;
  users: number;
  onOpen: () => void;
}

function DsCard({ ds, queries, users, onOpen }: CardProps) {
  const plan = REVIEW_PLANS.find((p) => p.id === ds.plan)!;
  return (
    <div
      onClick={onOpen}
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 16,
        cursor: 'pointer',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 12 }}>
        <div
          style={{
            width: 36,
            height: 36,
            borderRadius: 8,
            background: ds.db_type === 'POSTGRESQL' ? '#dbeafe' : '#fef3c7',
            color: ds.db_type === 'POSTGRESQL' ? '#1e40af' : '#92400e',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          <DatabaseOutlined style={{ fontSize: 18 }} />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 2 }}>{ds.name}</div>
          <div
            className="mono muted"
            style={{
              fontSize: 11,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            {ds.host}:{ds.port} / {ds.database_name}
          </div>
        </div>
        {ds.active ? (
          <Pill
            fg="var(--risk-low)"
            bg="var(--risk-low-bg)"
            border="var(--risk-low-border)"
            withDot
            size="sm"
          >
            active
          </Pill>
        ) : (
          <Pill
            fg="var(--fg-muted)"
            bg="var(--status-neutral-bg)"
            border="var(--status-neutral-border)"
            withDot
            size="sm"
          >
            inactive
          </Pill>
        )}
      </div>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 12 }}>
        <Pill bg="var(--bg-sunken)" size="sm">
          {ds.db_type}
        </Pill>
        <Pill bg="var(--bg-sunken)" size="sm">
          SSL · {ds.ssl_mode}
        </Pill>
        {ds.ai_enabled && (
          <Pill
            fg="var(--accent)"
            bg="var(--accent-bg)"
            border="var(--accent-border)"
            size="sm"
          >
            <ThunderboltOutlined style={{ fontSize: 9 }} /> AI
          </Pill>
        )}
      </div>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(3, 1fr)',
          gap: 8,
          paddingTop: 12,
          borderTop: '1px solid var(--border)',
        }}
      >
        <Stat label="plan" value={plan.name.split(' ')[0]!} />
        <Stat label="users" value={users} />
        <Stat label="queries" value={queries} />
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string | number }) {
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
      <div style={{ fontSize: 16, fontWeight: 600, fontFamily: 'var(--font-mono)' }}>{value}</div>
    </div>
  );
}
