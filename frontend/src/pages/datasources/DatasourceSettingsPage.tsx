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
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const ds = DATASOURCES.find((d) => d.id === id);
  const navigate = useNavigate();
  const [tab, setTab] = useState('config');
  const [testing, setTesting] = useState<'idle' | 'running' | 'ok'>('idle');

  if (!ds) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div className="muted">{t('datasources.settings.not_found_message')}</div>
        <Button onClick={() => navigate('/datasources')} style={{ marginTop: 12 }}>
          {t('datasources.settings.back_to_list')}
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
              {t('datasources.settings.back_button')}
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
              {testing === 'ok' ? t('datasources.settings.connection_ok', { ms: 42 }) : t('datasources.settings.test_connection')}
            </Button>
          </>
        }
      />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        style={{ padding: '0 28px' }}
        items={[
          { key: 'config', label: t('datasources.settings.tab_config') },
          {
            key: 'permissions',
            label: t('datasources.settings.tab_permissions', { count: PERMS.filter((p) => p.datasource_id === ds.id).length }),
          },
          { key: 'schema', label: t('datasources.settings.tab_schema') },
          { key: 'activity', label: t('datasources.settings.tab_activity') },
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
  const { t } = useTranslation();
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
        <Section title={t('datasources.settings.section_connection')}>
          <Grid>
            <Form.Item label={t('datasources.settings.label_name')} name="name"><Input /></Form.Item>
            <Form.Item label={t('datasources.settings.label_db_type')} name="db_type">
              <Select options={[{ value: 'POSTGRESQL' }, { value: 'MYSQL' }]} />
            </Form.Item>
            <Form.Item label={t('datasources.settings.label_host')} name="host"><Input /></Form.Item>
            <Form.Item label={t('datasources.settings.label_port')} name="port"><Input className="mono" /></Form.Item>
            <Form.Item label={t('datasources.settings.label_database_name')} name="database_name"><Input /></Form.Item>
            <Form.Item label={t('datasources.settings.label_ssl_mode')} name="ssl_mode">
              <Select options={[{ value: 'DISABLE' }, { value: 'REQUIRE' }, { value: 'VERIFY_CA' }, { value: 'VERIFY_FULL' }]} />
            </Form.Item>
            <Form.Item label={t('datasources.settings.label_service_account')}>
              <Input className="mono" defaultValue="accessflow_svc" />
            </Form.Item>
            <Form.Item label={t('datasources.settings.label_password')}>
              <Input.Password placeholder={t('datasources.settings.password_placeholder')} />
            </Form.Item>
          </Grid>
        </Section>
        <Section title={t('datasources.settings.section_limits')}>
          <Grid>
            <Form.Item label={t('datasources.settings.label_pool_size')} name="pool"><Input className="mono" /></Form.Item>
            <Form.Item label={t('datasources.settings.label_max_rows')} name="max_rows"><Input className="mono" /></Form.Item>
            <Form.Item label={t('datasources.settings.label_review_plan')} name="plan">
              <Select options={REVIEW_PLANS.map((p) => ({ value: p.id, label: p.name }))} />
            </Form.Item>
            <div />
            <Form.Item label={t('datasources.settings.label_require_writes')} name="require_review_writes" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item label={t('datasources.settings.label_require_reads')} name="require_review_reads" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item label={t('datasources.settings.label_ai_enabled')} name="ai_enabled" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item label={t('datasources.settings.label_active')} name="active" valuePropName="checked">
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
          <Button type="primary" icon={<CheckOutlined />}>{t('common.save_changes')}</Button>
          <Button>{t('common.cancel')}</Button>
          <div style={{ flex: 1 }} />
          <Button danger icon={<DeleteOutlined />}>{t('datasources.settings.soft_delete')}</Button>
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
  const { t } = useTranslation();
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
          <div style={{ fontWeight: 600 }}>{t('datasources.settings.permissions_title')}</div>
          <div className="muted" style={{ fontSize: 12 }}>
            {t('datasources.settings.permissions_subtitle')}
          </div>
        </div>
        <Button type="primary" icon={<PlusOutlined />}>{t('datasources.settings.grant_access')}</Button>
      </div>
      <Table
        rowKey="id"
        size="middle"
        dataSource={users}
        pagination={false}
        columns={[
          {
            title: t('datasources.settings.perm_col_user'),
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
            title: t('datasources.settings.perm_col_read'),
            width: 70,
            align: 'center',
            render: (_v, u) => <PermCell on={u.role === 'ADMIN' || !!findPerm(u.id)?.can_read} />,
          },
          {
            title: t('datasources.settings.perm_col_write'),
            width: 70,
            align: 'center',
            render: (_v, u) => <PermCell on={u.role === 'ADMIN' || !!findPerm(u.id)?.can_write} />,
          },
          {
            title: t('datasources.settings.perm_col_ddl'),
            width: 70,
            align: 'center',
            render: (_v, u) => <PermCell on={u.role === 'ADMIN' || !!findPerm(u.id)?.can_ddl} />,
          },
          {
            title: t('datasources.settings.perm_col_row_limit'),
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
            title: t('datasources.settings.perm_col_schemas'),
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
            title: t('datasources.settings.perm_col_expires'),
            width: 130,
            render: (_v, u) => {
              if (u.role === 'ADMIN') return <span>—</span>;
              const p = findPerm(u.id);
              return (
                <span className="muted" style={{ fontSize: 12 }}>
                  {p?.expires_at ? fmtDate(p.expires_at) : t('datasources.settings.perm_never_expires')}
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
  const { t } = useTranslation();
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
            title: t('datasources.settings.schema_col_table'),
            dataIndex: 'name',
            render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
          },
          {
            title: t('datasources.settings.schema_col_columns'),
            render: (_v, tb) => <span className="mono muted">{tb.columns.length}</span>,
          },
          {
            title: t('datasources.settings.schema_col_pk'),
            render: (_v, tb) => (
              <span className="mono" style={{ fontSize: 12 }}>
                {tb.columns.find((c) => c.primary_key)?.name ?? '—'}
              </span>
            ),
          },
        ]}
      />
    </div>
  );
}

function ActivityTab({ dsId }: { dsId: string }) {
  const { t } = useTranslation();
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
          { title: t('datasources.settings.activity_col_id'), dataIndex: 'id', render: (v) => <span className="mono muted">{v}</span> },
          { title: t('datasources.settings.activity_col_type'), dataIndex: 'query_type', render: (v) => <QueryTypePill type={v} size="sm" /> },
          { title: t('datasources.settings.activity_col_status'), dataIndex: 'status', render: (v) => <StatusPill status={v} size="sm" /> },
          { title: t('datasources.settings.activity_col_submitter'), dataIndex: 'submitter_name' },
          {
            title: t('datasources.settings.activity_col_when'),
            dataIndex: 'created_at',
            render: (v) => <span className="muted">{timeAgo(v)}</span>,
          },
        ]}
      />
    </div>
  );
}
