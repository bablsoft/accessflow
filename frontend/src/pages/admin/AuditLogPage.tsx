import { useMemo, useState } from 'react';
import { Button, Drawer, Input, Select, Table } from 'antd';
import { DownloadOutlined, FilterOutlined, SearchOutlined } from '@ant-design/icons';
import { PageHeader } from '@/components/common/PageHeader';
import { Avatar } from '@/components/common/Avatar';
import { AUDIT } from '@/mocks/data';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import type { AuditEvent } from '@/types/api';

const actionColor = (a: string): string => {
  if (a.includes('REJECTED') || a.includes('FAILED') || a.includes('DEACTIVATED') || a.includes('REVOKED')) return 'var(--risk-crit)';
  if (a.includes('APPROVED') || a.includes('EXECUTED') || a.includes('CREATED')) return 'var(--risk-low)';
  if (a.includes('LOGIN_FAILED')) return 'var(--risk-high)';
  return 'var(--fg)';
};

export function AuditLogPage() {
  const [q, setQ] = useState('');
  const [action, setAction] = useState<string>('all');
  const [detail, setDetail] = useState<AuditEvent | null>(null);

  const actions = useMemo(() => [...new Set(AUDIT.map((a) => a.action))], []);
  const filtered = useMemo(
    () =>
      AUDIT.filter((a) => {
        if (action !== 'all' && a.action !== action) return false;
        if (
          q &&
          !a.actor_name.toLowerCase().includes(q.toLowerCase()) &&
          !a.action.toLowerCase().includes(q.toLowerCase()) &&
          !a.resource_id.includes(q)
        )
          return false;
        return true;
      }),
    [q, action],
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title="Audit log"
        subtitle="Append-only, tamper-evident log of every action in the system."
        actions={
          <>
            <Button icon={<FilterOutlined />}>Advanced filters</Button>
            <Button icon={<DownloadOutlined />}>Export</Button>
          </>
        }
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
          placeholder="Search actor, action, resource…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ width: 280 }}
        />
        <Select
          value={action}
          onChange={setAction}
          style={{ width: 220 }}
          options={[{ value: 'all', label: 'All actions' }, ...actions.map((a) => ({ value: a, label: a }))]}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11, alignSelf: 'center' }}>
          {filtered.length} events
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        <Table
          rowKey="id"
          size="middle"
          dataSource={filtered}
          pagination={{ pageSize: 20 }}
          onRow={(record) => ({
            onClick: () => setDetail(record),
            style: { cursor: 'pointer' },
          })}
          columns={[
            {
              title: 'When',
              dataIndex: 'created_at',
              width: 120,
              render: (v) => <span className="muted">{timeAgo(v)}</span>,
            },
            {
              title: 'Actor',
              dataIndex: 'actor_name',
              width: 200,
              render: (v) => (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Avatar name={v} size={20} />
                  <span style={{ fontSize: 12 }}>{v}</span>
                </div>
              ),
            },
            {
              title: 'Action',
              dataIndex: 'action',
              width: 230,
              render: (v) => (
                <span
                  className="mono"
                  style={{ fontSize: 11.5, fontWeight: 500, color: actionColor(v) }}
                >
                  {v}
                </span>
              ),
            },
            {
              title: 'Resource',
              dataIndex: 'resource_type',
              width: 150,
              render: (v) => <span className="mono" style={{ fontSize: 11.5 }}>{v}</span>,
            },
            {
              title: 'Resource ID',
              dataIndex: 'resource_id',
              render: (v) => <span className="mono muted" style={{ fontSize: 11.5 }}>{v}</span>,
            },
            {
              title: 'IP',
              dataIndex: 'ip_address',
              width: 130,
              render: (v) => <span className="mono muted" style={{ fontSize: 11.5 }}>{v}</span>,
            },
          ]}
        />
      </div>
      <Drawer
        open={!!detail}
        onClose={() => setDetail(null)}
        title="Audit event detail"
        width={520}
      >
        {detail && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <Card>
              <div
                className="muted"
                style={{
                  fontSize: 11,
                  textTransform: 'uppercase',
                  letterSpacing: '0.04em',
                  marginBottom: 6,
                }}
              >
                Action
              </div>
              <div
                className="mono"
                style={{ fontSize: 14, fontWeight: 600, color: actionColor(detail.action) }}
              >
                {detail.action}
              </div>
            </Card>
            <Card>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                <Row k="audit.id" v={detail.id} />
                <Row k="actor.id" v={detail.actor_id} />
                <Row k="actor.email" v={detail.actor_email} />
                <Row k="resource.type" v={detail.resource_type} />
                <Row k="resource.id" v={detail.resource_id} />
                <Row k="ip" v={detail.ip_address} />
                <Row k="created_at" v={fmtDate(detail.created_at)} />
              </div>
            </Card>
            <Card title="metadata · JSON">
              <pre
                style={{
                  margin: 0,
                  padding: 14,
                  fontFamily: 'var(--font-mono)',
                  fontSize: 11.5,
                  lineHeight: 1.5,
                  background: 'var(--bg-code)',
                  borderRadius: '0 0 var(--radius-md) var(--radius-md)',
                }}
              >
                {JSON.stringify(
                  {
                    resource_id: detail.resource_id,
                    user_agent: detail.user_agent,
                    organization_id: 'acme-001',
                  },
                  null,
                  2,
                )}
              </pre>
            </Card>
          </div>
        )}
      </Drawer>
    </div>
  );
}

function Card({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
      }}
    >
      {title && (
        <div
          style={{
            padding: '10px 14px',
            borderBottom: '1px solid var(--border)',
            fontWeight: 600,
            fontSize: 12,
          }}
        >
          {title}
        </div>
      )}
      {!title ? <div style={{ padding: 14 }}>{children}</div> : children}
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        gap: 8,
        fontFamily: 'var(--font-mono)',
        fontSize: 12,
      }}
    >
      <span className="muted">{k}</span>
      <span style={{ textAlign: 'right' }}>{v}</span>
    </div>
  );
}
