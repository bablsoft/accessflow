import { useMemo, useState } from 'react';
import { Button, Form, Input, Select, Switch, Table, Tabs } from 'antd';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  DeleteOutlined,
  LoadingOutlined,
  PlayCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { PageHeader } from '@/components/common/PageHeader';
import { Avatar } from '@/components/common/Avatar';
import { StatusPill } from '@/components/common/StatusPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { DATASOURCES, PERMS, REVIEW_PLANS, USERS } from '@/mocks/data';
import { buildMockSchema } from '@/mocks/schema';
import { useQueriesStore } from '@/store/queriesStore';
import { fmtDate, fmtNum, timeAgo } from '@/utils/dateFormat';
import { testConnection } from '@/api/datasources';

export function DatasourceSettingsPage() {
  const { id } = useParams<{ id: string }>();
  const ds = DATASOURCES.find((d) => d.id === id);
  const navigate = useNavigate();
  const [tab, setTab] = useState('config');
  const [testing, setTesting] = useState<'idle' | 'running' | 'ok'>('idle');

  if (!ds) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div className="muted">Datasource not found.</div>
        <Button onClick={() => navigate('/datasources')} style={{ marginTop: 12 }}>
          Back to list
        </Button>
      </div>
    );
  }

  const onTest = async () => {
    setTesting('running');
    await testConnection(ds.id);
    setTesting('ok');
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        breadcrumbs={['Datasources', ds.name]}
        title={ds.name}
        subtitle={
          <>
            <span className="mono">{ds.db_type}</span> · {ds.host}:{ds.port} /{' '}
            {ds.database_name}
          </>
        }
        actions={
          <>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/datasources')}>
              Back
            </Button>
            <Button
              icon={
                testing === 'running' ? (
                  <LoadingOutlined />
                ) : testing === 'ok' ? (
                  <CheckOutlined style={{ color: 'var(--risk-low)' }} />
                ) : (
                  <PlayCircleOutlined />
                )
              }
              onClick={onTest}
            >
              {testing === 'ok' ? 'Connected · 42ms' : 'Test connection'}
            </Button>
          </>
        }
      />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        style={{ padding: '0 28px' }}
        items={[
          { key: 'config', label: 'Configuration' },
          {
            key: 'permissions',
            label: `Permissions · ${PERMS.filter((p) => p.datasource_id === ds.id).length}`,
          },
          { key: 'schema', label: 'Schema' },
          { key: 'activity', label: 'Activity' },
        ]}
      />
      <div style={{ flex: 1, overflow: 'auto' }}>
        {tab === 'config' && <ConfigTab ds={ds} />}
        {tab === 'permissions' && <PermissionMatrix dsId={ds.id} />}
        {tab === 'schema' && <SchemaTab dsId={ds.id} />}
        {tab === 'activity' && <ActivityTab dsId={ds.id} />}
      </div>
    </div>
  );
}

function ConfigTab({ ds }: { ds: (typeof DATASOURCES)[number] }) {
  const [form] = Form.useForm();
  return (
    <div style={{ padding: 28, maxWidth: 800 }}>
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          name: ds.name,
          db_type: ds.db_type,
          host: ds.host,
          port: ds.port,
          database_name: ds.database_name,
          ssl_mode: ds.ssl_mode,
          pool: ds.pool,
          max_rows: ds.max_rows,
          plan: ds.plan,
          require_review_writes: ds.require_review_writes,
          require_review_reads: ds.require_review_reads,
          ai_enabled: ds.ai_enabled,
          active: ds.active,
        }}
      >
        <Section title="Connection">
          <Grid>
            <Form.Item label="Name" name="name"><Input /></Form.Item>
            <Form.Item label="Database type" name="db_type">
              <Select options={[{ value: 'POSTGRESQL' }, { value: 'MYSQL' }]} />
            </Form.Item>
            <Form.Item label="Host" name="host"><Input /></Form.Item>
            <Form.Item label="Port" name="port"><Input className="mono" /></Form.Item>
            <Form.Item label="Database name" name="database_name"><Input /></Form.Item>
            <Form.Item label="SSL mode" name="ssl_mode">
              <Select options={[{ value: 'DISABLE' }, { value: 'REQUIRE' }, { value: 'VERIFY_CA' }, { value: 'VERIFY_FULL' }]} />
            </Form.Item>
            <Form.Item label="Service account">
              <Input className="mono" defaultValue="accessflow_svc" />
            </Form.Item>
            <Form.Item label="Password">
              <Input.Password placeholder="Leave blank to keep existing" />
            </Form.Item>
          </Grid>
        </Section>
        <Section title="Limits & policies">
          <Grid>
            <Form.Item label="Connection pool size" name="pool"><Input className="mono" /></Form.Item>
            <Form.Item label="Max rows per query" name="max_rows"><Input className="mono" /></Form.Item>
            <Form.Item label="Review plan" name="plan">
              <Select options={REVIEW_PLANS.map((p) => ({ value: p.id, label: p.name }))} />
            </Form.Item>
            <div />
            <Form.Item label="Require review for writes" name="require_review_writes" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item label="Require review for reads" name="require_review_reads" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item label="Enable AI query analysis" name="ai_enabled" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item label="Datasource active" name="active" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Grid>
        </Section>
        <div
          style={{
            display: 'flex',
            gap: 8,
            paddingTop: 16,
            borderTop: '1px solid var(--border)',
          }}
        >
          <Button type="primary" icon={<CheckOutlined />}>Save changes</Button>
          <Button>Cancel</Button>
          <div style={{ flex: 1 }} />
          <Button danger icon={<DeleteOutlined />}>Soft-delete datasource</Button>
        </div>
      </Form>
    </div>
  );
}
function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 32 }}>
      <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 14 }}>{title}</div>
      {children}
    </div>
  );
}
function Grid({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>{children}</div>
  );
}

function PermissionMatrix({ dsId }: { dsId: string }) {
  const users = useMemo(() => USERS.filter((u) => u.active), []);
  const findPerm = (uid: string) =>
    PERMS.find((p) => p.datasource_id === dsId && p.user_id === uid);

  return (
    <div style={{ padding: 28 }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 16,
        }}
      >
        <div>
          <div style={{ fontWeight: 600 }}>User permissions</div>
          <div className="muted" style={{ fontSize: 12 }}>
            Granular per-user grants for this datasource. Admins automatically have full access.
          </div>
        </div>
        <Button type="primary" icon={<PlusOutlined />}>Grant access</Button>
      </div>
      <Table
        rowKey="id"
        size="middle"
        dataSource={users}
        pagination={false}
        columns={[
          {
            title: 'User',
            dataIndex: 'display_name',
            render: (_v, u) => (
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <Avatar name={u.display_name} size={24} />
                <div>
                  <div style={{ fontSize: 13 }}>{u.display_name}</div>
                  <div className="mono muted" style={{ fontSize: 11 }}>{u.role}</div>
                </div>
              </div>
            ),
          },
          {
            title: 'Read',
            width: 70,
            align: 'center',
            render: (_v, u) => <PermCell on={u.role === 'ADMIN' || !!findPerm(u.id)?.can_read} />,
          },
          {
            title: 'Write',
            width: 70,
            align: 'center',
            render: (_v, u) => <PermCell on={u.role === 'ADMIN' || !!findPerm(u.id)?.can_write} />,
          },
          {
            title: 'DDL',
            width: 70,
            align: 'center',
            render: (_v, u) => <PermCell on={u.role === 'ADMIN' || !!findPerm(u.id)?.can_ddl} />,
          },
          {
            title: 'Row limit',
            width: 110,
            render: (_v, u) => {
              if (u.role === 'ADMIN') return <span className="mono muted">∞</span>;
              const p = findPerm(u.id);
              return p?.row_limit ? (
                <span className="mono">{fmtNum(p.row_limit)}</span>
              ) : (
                <span className="muted">default</span>
              );
            },
          },
          {
            title: 'Allowed schemas',
            render: (_v, u) => {
              if (u.role === 'ADMIN') return <span className="muted">all</span>;
              const p = findPerm(u.id);
              return (
                <span className="mono" style={{ fontSize: 12 }}>
                  {p?.allowed_schemas ? p.allowed_schemas.join(', ') : <span className="muted">all</span>}
                </span>
              );
            },
          },
          {
            title: 'Expires',
            width: 130,
            render: (_v, u) => {
              if (u.role === 'ADMIN') return <span>—</span>;
              const p = findPerm(u.id);
              return (
                <span className="muted" style={{ fontSize: 12 }}>
                  {p?.expires_at ? fmtDate(p.expires_at) : 'never'}
                </span>
              );
            },
          },
        ]}
      />
    </div>
  );
}

function PermCell({ on }: { on: boolean }) {
  return on ? (
    <span
      style={{
        display: 'inline-flex',
        width: 20,
        height: 20,
        borderRadius: 4,
        background: 'var(--risk-low-bg)',
        color: 'var(--risk-low)',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <CheckOutlined style={{ fontSize: 12 }} />
    </span>
  ) : (
    <span
      style={{
        display: 'inline-block',
        width: 12,
        height: 2,
        background: 'var(--border-strong)',
        borderRadius: 1,
      }}
    />
  );
}

function SchemaTab({ dsId }: { dsId: string }) {
  const ds = DATASOURCES.find((d) => d.id === dsId)!;
  const schema = useMemo(() => buildMockSchema(ds), [ds]);
  return (
    <div style={{ padding: 28 }}>
      <Table
        rowKey="name"
        size="middle"
        dataSource={schema.schemas[0]!.tables}
        pagination={false}
        columns={[
          {
            title: 'Table',
            dataIndex: 'name',
            render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
          },
          {
            title: 'Columns',
            render: (_v, t) => <span className="mono muted">{t.columns.length}</span>,
          },
          {
            title: 'Primary key',
            render: (_v, t) => (
              <span className="mono" style={{ fontSize: 12 }}>
                {t.columns.find((c) => c.primary_key)?.name ?? '—'}
              </span>
            ),
          },
        ]}
      />
    </div>
  );
}

function ActivityTab({ dsId }: { dsId: string }) {
  const queries = useQueriesStore((s) =>
    s.queries.filter((q) => q.datasource_id === dsId).slice(0, 20),
  );
  return (
    <div style={{ padding: 28 }}>
      <Table
        rowKey="id"
        size="middle"
        dataSource={queries}
        pagination={false}
        columns={[
          { title: 'ID', dataIndex: 'id', render: (v) => <span className="mono muted">{v}</span> },
          { title: 'Type', dataIndex: 'query_type', render: (v) => <QueryTypePill type={v} size="sm" /> },
          { title: 'Status', dataIndex: 'status', render: (v) => <StatusPill status={v} size="sm" /> },
          { title: 'Submitter', dataIndex: 'submitter_name' },
          {
            title: 'When',
            dataIndex: 'created_at',
            render: (v) => <span className="muted">{timeAgo(v)}</span>,
          },
        ]}
      />
    </div>
  );
}
