import { useMemo, useState } from 'react';
import {
  Button,
  DatePicker,
  Drawer,
  Input,
  Select,
  Skeleton,
  Table,
} from 'antd';
import type { Dayjs } from 'dayjs';
import { ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Avatar } from '@/components/common/Avatar';
import { auditKeys, listAuditEvents } from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import { userDisplay } from '@/utils/userDisplay';
import type { AuditEvent, AuditLogFilters } from '@/types/api';

const PAGE_SIZE = 20;

const RESOURCE_TYPES = [
  'query_request',
  'datasource',
  'user',
  'permission',
  'review_plan',
  'notification_channel',
];

const ACTIONS = [
  'QUERY_SUBMITTED',
  'QUERY_AI_ANALYZED',
  'QUERY_AI_FAILED',
  'QUERY_REVIEW_REQUESTED',
  'QUERY_APPROVED',
  'QUERY_REJECTED',
  'QUERY_EXECUTED',
  'QUERY_FAILED',
  'QUERY_CANCELLED',
  'DATASOURCE_CREATED',
  'DATASOURCE_UPDATED',
  'PERMISSION_GRANTED',
  'PERMISSION_REVOKED',
  'REVIEW_PLAN_CREATED',
  'REVIEW_PLAN_UPDATED',
  'REVIEW_PLAN_DELETED',
  'USER_LOGIN',
  'USER_LOGIN_FAILED',
  'USER_CREATED',
  'USER_DEACTIVATED',
];

const actionColor = (a: string): string => {
  if (
    a.includes('REJECTED') ||
    a.includes('FAILED') ||
    a.includes('DEACTIVATED') ||
    a.includes('REVOKED')
  ) {
    return 'var(--risk-crit)';
  }
  if (a.includes('APPROVED') || a.includes('EXECUTED') || a.includes('CREATED')) {
    return 'var(--risk-low)';
  }
  if (a.includes('LOGIN_FAILED')) return 'var(--risk-high)';
  return 'var(--fg)';
};

export function AuditLogPage() {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [action, setAction] = useState<string>('all');
  const [resourceType, setResourceType] = useState<string>('all');
  const [actorId, setActorId] = useState('');
  const [resourceId, setResourceId] = useState('');
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [detail, setDetail] = useState<AuditEvent | null>(null);

  const filters: AuditLogFilters = useMemo(
    () => ({
      page,
      size: PAGE_SIZE,
      sort: 'createdAt,DESC',
      action: action === 'all' ? undefined : action,
      resource_type: resourceType === 'all' ? undefined : resourceType,
      actor_id: actorId.trim() || undefined,
      resource_id: resourceId.trim() || undefined,
      from: range?.[0]?.toISOString(),
      to: range?.[1]?.toISOString(),
    }),
    [page, action, resourceType, actorId, resourceId, range],
  );

  const auditQuery = useQuery({
    queryKey: auditKeys.list(filters),
    queryFn: () => listAuditEvents(filters),
  });

  const events = auditQuery.data?.content ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.audit.title')}
        subtitle={t('admin.audit.subtitle')}
        actions={
          <Button icon={<ReloadOutlined />} onClick={() => auditQuery.refetch()}>
            {t('common.refresh')}
          </Button>
        }
      />
      <div
        style={{
          padding: '12px 28px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-elev)',
          display: 'flex',
          flexWrap: 'wrap',
          gap: 8,
        }}
      >
        <Select
          value={action}
          onChange={(v) => {
            setAction(v);
            setPage(0);
          }}
          style={{ width: 220 }}
          options={[
            { value: 'all', label: t('admin.audit.filter_all_actions') },
            ...ACTIONS.map((a) => ({ value: a, label: a })),
          ]}
        />
        <Select
          value={resourceType}
          onChange={(v) => {
            setResourceType(v);
            setPage(0);
          }}
          style={{ width: 200 }}
          options={[
            { value: 'all', label: t('admin.audit.filter_all_resources') },
            ...RESOURCE_TYPES.map((r) => ({ value: r, label: r })),
          ]}
        />
        <Input
          placeholder={t('admin.audit.filter_actor_placeholder')}
          value={actorId}
          onChange={(e) => {
            setActorId(e.target.value);
            setPage(0);
          }}
          style={{ width: 240 }}
          className="mono"
        />
        <Input
          placeholder={t('admin.audit.filter_resource_id_placeholder')}
          value={resourceId}
          onChange={(e) => {
            setResourceId(e.target.value);
            setPage(0);
          }}
          style={{ width: 240 }}
          className="mono"
        />
        <DatePicker.RangePicker
          showTime
          value={range as [Dayjs | null, Dayjs | null]}
          onChange={(v) => {
            setRange(v as [Dayjs | null, Dayjs | null] | null);
            setPage(0);
          }}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11, alignSelf: 'center' }}>
          {t('admin.audit.count_label', {
            count: auditQuery.data?.total_elements ?? 0,
          })}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {auditQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 8 }} style={{ padding: 24 }} />
        ) : auditQuery.isError ? (
          <EmptyState
            title={t('admin.audit.load_error')}
            description={adminErrorMessage(auditQuery.error)}
          />
        ) : events.length === 0 ? (
          <EmptyState title={t('admin.audit.title')} description={t('admin.audit.empty')} />
        ) : (
          <Table<AuditEvent>
            rowKey="id"
            size="middle"
            dataSource={events}
            pagination={{
              pageSize: PAGE_SIZE,
              current: page + 1,
              total: auditQuery.data?.total_elements ?? 0,
              onChange: (p) => setPage(p - 1),
            }}
            onRow={(record) => ({
              onClick: () => setDetail(record),
              style: { cursor: 'pointer' },
            })}
            columns={[
              {
                title: t('admin.audit.col_when'),
                dataIndex: 'created_at',
                width: 120,
                render: (v) => <span className="muted">{timeAgo(v)}</span>,
              },
              {
                title: t('admin.audit.col_actor'),
                width: 220,
                render: (_v, e) => {
                  const name = userDisplay(e.actor_display_name, e.actor_email)
                    || t('admin.audit.actor_unknown');
                  return (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <Avatar name={name} size={20} />
                      <div>
                        <div style={{ fontSize: 12 }}>{name}</div>
                        {e.actor_email && (
                          <div className="mono muted" style={{ fontSize: 10 }}>
                            {e.actor_email}
                          </div>
                        )}
                      </div>
                    </div>
                  );
                },
              },
              {
                title: t('admin.audit.col_action'),
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
                title: t('admin.audit.col_resource'),
                dataIndex: 'resource_type',
                width: 150,
                render: (v) => (
                  <span className="mono" style={{ fontSize: 11.5 }}>
                    {v}
                  </span>
                ),
              },
              {
                title: t('admin.audit.col_resource_id'),
                dataIndex: 'resource_id',
                render: (v: string | null) =>
                  v ? (
                    <span className="mono muted" style={{ fontSize: 11.5 }}>
                      {v}
                    </span>
                  ) : (
                    <span className="muted">—</span>
                  ),
              },
              {
                title: t('admin.audit.col_ip'),
                dataIndex: 'ip_address',
                width: 130,
                render: (v: string | null) =>
                  v ? (
                    <span className="mono muted" style={{ fontSize: 11.5 }}>
                      {v}
                    </span>
                  ) : (
                    <span className="muted">—</span>
                  ),
              },
            ]}
          />
        )}
      </div>
      <Drawer
        open={!!detail}
        onClose={() => setDetail(null)}
        title={t('admin.audit.drawer_title')}
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
                {t('admin.audit.section_action')}
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
                <Row k="organization_id" v={detail.organization_id} />
                <Row k="actor.id" v={detail.actor_id ?? '—'} />
                <Row k="actor.email" v={detail.actor_email ?? '—'} />
                <Row k="actor.display_name" v={detail.actor_display_name ?? '—'} />
                <Row k="resource.type" v={detail.resource_type} />
                <Row k="resource.id" v={detail.resource_id ?? '—'} />
                <Row k="ip" v={detail.ip_address ?? '—'} />
                <Row k="user_agent" v={detail.user_agent ?? '—'} />
                <Row k="created_at" v={fmtDate(detail.created_at)} />
              </div>
            </Card>
            <Card title={t('admin.audit.section_metadata')}>
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
                {JSON.stringify(detail.metadata ?? {}, null, 2)}
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
