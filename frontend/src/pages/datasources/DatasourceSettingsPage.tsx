import { useState } from 'react';
import { App, Button, Form, Input, Select, Switch, Table, Tabs } from 'antd';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  DeleteOutlined,
  LoadingOutlined,
  PlayCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { isAxiosError } from 'axios';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { Avatar } from '@/components/common/Avatar';
import { StatusPill } from '@/components/common/StatusPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { useQueriesStore } from '@/store/queriesStore';
import { fmtDate, fmtNum, timeAgo } from '@/utils/dateFormat';
import {
  datasourceKeys,
  deleteDatasource,
  getDatasource,
  listPermissions,
  revokePermission,
  testConnection,
  updateDatasource,
} from '@/api/datasources';
import { useSchemaIntrospect } from '@/hooks/useSchemaIntrospect';
import type { Datasource, DatasourcePermission, UpdateDatasourceInput } from '@/types/api';

export function DatasourceSettingsPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState('config');

  const dsQuery = useQuery({
    queryKey: id ? datasourceKeys.detail(id) : ['datasources', 'detail', 'idle'],
    queryFn: () => getDatasource(id!),
    enabled: !!id,
    retry: false,
  });

  const permissionsQuery = useQuery({
    queryKey: id ? datasourceKeys.permissions(id) : ['datasources', 'permissions', 'idle'],
    queryFn: () => listPermissions(id!),
    enabled: !!id,
  });

  const testMutation = useMutation({
    mutationFn: () => testConnection(id!),
    onSuccess: (result) => {
      if (result.ok) {
        message.success(t('datasources.settings.connection_ok', { ms: result.latency_ms }));
      } else {
        message.error(result.message ?? t('datasources.settings.connection_failed'));
      }
    },
    onError: () => {
      message.error(t('datasources.settings.connection_failed'));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteDatasource(id!),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: datasourceKeys.lists() });
      message.success(t('datasources.settings.delete_success'));
      navigate('/datasources');
    },
    onError: () => {
      message.error(t('datasources.settings.delete_error'));
    },
  });

  const onTest = () => testMutation.mutate();

  const onDelete = () => {
    if (!dsQuery.data) return;
    modal.confirm({
      title: t('datasources.settings.delete_confirm_title'),
      content: t('datasources.settings.delete_confirm_body', { name: dsQuery.data.name }),
      okType: 'danger',
      okText: t('datasources.settings.soft_delete'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(),
    });
  };

  if (dsQuery.isLoading) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div className="muted">{t('datasources.settings.loading')}</div>
      </div>
    );
  }

  if (dsQuery.isError || !dsQuery.data) {
    const status = isAxiosError(dsQuery.error) ? dsQuery.error.response?.status : undefined;
    const isNotFound = status === 404;
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div className="muted">
          {isNotFound
            ? t('datasources.settings.not_found_message')
            : t('datasources.settings.load_error')}
        </div>
        <Button onClick={() => navigate('/datasources')} style={{ marginTop: 12 }}>
          {t('datasources.settings.back_to_list')}
        </Button>
      </div>
    );
  }

  const ds = dsQuery.data;
  const permissionsCount = permissionsQuery.data?.length ?? 0;
  const testIcon =
    testMutation.isPending ? (
      <LoadingOutlined />
    ) : testMutation.data?.ok ? (
      <CheckOutlined style={{ color: 'var(--risk-low)' }} />
    ) : (
      <PlayCircleOutlined />
    );

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
            <Button icon={testIcon} onClick={onTest} disabled={testMutation.isPending}>
              {testMutation.data?.ok
                ? t('datasources.settings.connection_ok', { ms: testMutation.data.latency_ms })
                : t('datasources.settings.test_connection')}
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
            label: t('datasources.settings.tab_permissions', { count: permissionsCount }),
          },
          { key: 'schema', label: t('datasources.settings.tab_schema') },
          { key: 'activity', label: t('datasources.settings.tab_activity') },
        ]}
      />
      <div style={{ flex: 1, overflow: 'auto' }}>
        {tab === 'config' && <ConfigTab ds={ds} onDelete={onDelete} deletePending={deleteMutation.isPending} />}
        {tab === 'permissions' && <PermissionMatrix dsId={ds.id} />}
        {tab === 'schema' && <SchemaTab dsId={ds.id} />}
        {tab === 'activity' && <ActivityTab dsId={ds.id} />}
      </div>
    </div>
  );
}

interface ConfigTabProps {
  ds: Datasource;
  onDelete: () => void;
  deletePending: boolean;
}

function ConfigTab({ ds, onDelete, deletePending }: ConfigTabProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<UpdateDatasourceInput>();

  const initialValues: UpdateDatasourceInput = {
    name: ds.name,
    host: ds.host,
    port: ds.port,
    database_name: ds.database_name,
    username: ds.username,
    ssl_mode: ds.ssl_mode,
    connection_pool_size: ds.connection_pool_size,
    max_rows_per_query: ds.max_rows_per_query,
    require_review_writes: ds.require_review_writes,
    require_review_reads: ds.require_review_reads,
    ai_analysis_enabled: ds.ai_analysis_enabled,
    active: ds.active,
  };

  const updateMutation = useMutation({
    mutationFn: (values: UpdateDatasourceInput) => updateDatasource(ds.id, values),
    onSuccess: (updated) => {
      queryClient.setQueryData(datasourceKeys.detail(ds.id), updated);
      void queryClient.invalidateQueries({ queryKey: datasourceKeys.lists() });
      message.success(t('datasources.settings.save_success'));
    },
    onError: () => {
      message.error(t('datasources.settings.save_error'));
    },
  });

  const onFinish = (values: UpdateDatasourceInput & { password?: string }) => {
    const body: UpdateDatasourceInput = { ...values };
    if (!body.password || body.password.trim().length === 0) {
      delete body.password;
    }
    updateMutation.mutate(body);
  };

  return (
    <div style={{ padding: 28, maxWidth: 800 }}>
      <Form form={form} layout="vertical" initialValues={initialValues} onFinish={onFinish}>
        <Section title={t('datasources.settings.section_connection')}>
          <Grid>
            <Form.Item
              label={t('datasources.settings.label_name')}
              name="name"
              rules={[{ required: true, max: 255 }]}
            >
              <Input />
            </Form.Item>
            <Form.Item label={t('datasources.settings.label_db_type')}>
              <Input value={ds.db_type} disabled />
            </Form.Item>
            <Form.Item
              label={t('datasources.settings.label_host')}
              name="host"
              rules={[{ required: true, max: 255 }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              label={t('datasources.settings.label_port')}
              name="port"
              rules={[{ required: true, type: 'number', min: 1, max: 65535 }]}
            >
              <Input className="mono" type="number" />
            </Form.Item>
            <Form.Item
              label={t('datasources.settings.label_database_name')}
              name="database_name"
              rules={[{ required: true, max: 255 }]}
            >
              <Input />
            </Form.Item>
            <Form.Item label={t('datasources.settings.label_ssl_mode')} name="ssl_mode">
              <Select
                options={[
                  { value: 'DISABLE' },
                  { value: 'REQUIRE' },
                  { value: 'VERIFY_CA' },
                  { value: 'VERIFY_FULL' },
                ]}
              />
            </Form.Item>
            <Form.Item
              label={t('datasources.settings.label_username')}
              name="username"
              rules={[{ required: true, max: 255 }]}
            >
              <Input className="mono" />
            </Form.Item>
            <Form.Item label={t('datasources.settings.label_password')} name="password">
              <Input.Password placeholder={t('datasources.settings.password_placeholder')} />
            </Form.Item>
          </Grid>
        </Section>
        <Section title={t('datasources.settings.section_limits')}>
          <Grid>
            <Form.Item
              label={t('datasources.settings.label_pool_size')}
              name="connection_pool_size"
              rules={[{ required: true, type: 'number', min: 1, max: 200 }]}
            >
              <Input className="mono" type="number" />
            </Form.Item>
            <Form.Item
              label={t('datasources.settings.label_max_rows')}
              name="max_rows_per_query"
              rules={[{ required: true, type: 'number', min: 1, max: 1_000_000 }]}
            >
              <Input className="mono" type="number" />
            </Form.Item>
            <Form.Item label={t('datasources.settings.label_review_plan')}>
              {/* TODO(FE-XX): swap to plans Select once /review-plans ships. */}
              <Input
                className="mono"
                value={ds.review_plan_id ?? ''}
                disabled
                placeholder={t('datasources.list.stat_plan_unknown')}
              />
            </Form.Item>
            <div />
            <Form.Item
              label={t('datasources.settings.label_require_writes')}
              name="require_review_writes"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              label={t('datasources.settings.label_require_reads')}
              name="require_review_reads"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              label={t('datasources.settings.label_ai_enabled')}
              name="ai_analysis_enabled"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              label={t('datasources.settings.label_active')}
              name="active"
              valuePropName="checked"
            >
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
          <Button
            type="primary"
            icon={updateMutation.isPending ? <LoadingOutlined /> : <CheckOutlined />}
            onClick={() => form.submit()}
            disabled={updateMutation.isPending}
          >
            {t('common.save_changes')}
          </Button>
          <Button onClick={() => form.resetFields()}>{t('common.cancel')}</Button>
          <div style={{ flex: 1 }} />
          <Button danger icon={<DeleteOutlined />} onClick={onDelete} disabled={deletePending}>
            {t('datasources.settings.soft_delete')}
          </Button>
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
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();

  const permissionsQuery = useQuery({
    queryKey: datasourceKeys.permissions(dsId),
    queryFn: () => listPermissions(dsId),
  });
  const permissions = permissionsQuery.data ?? [];

  const revokeMutation = useMutation({
    mutationFn: (permId: string) => revokePermission(dsId, permId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: datasourceKeys.permissions(dsId) });
      message.success(t('datasources.settings.revoke_success'));
    },
    onError: () => {
      message.error(t('datasources.settings.revoke_error'));
    },
  });

  const onGrantPlaceholder = () => {
    modal.info({
      title: t('datasources.settings.grant_unavailable_title'),
      content: t('datasources.settings.grant_unavailable_body'),
    });
  };

  const onRevoke = (perm: DatasourcePermission) => {
    modal.confirm({
      title: t('datasources.settings.revoke_confirm_title'),
      content: perm.user_display_name,
      okType: 'danger',
      okText: t('datasources.settings.perm_revoke'),
      cancelText: t('common.cancel'),
      onOk: () => revokeMutation.mutateAsync(perm.id),
    });
  };

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
        <Button type="primary" icon={<PlusOutlined />} onClick={onGrantPlaceholder}>
          {t('datasources.settings.grant_access')}
        </Button>
      </div>
      <Table<DatasourcePermission>
        rowKey="id"
        size="middle"
        loading={permissionsQuery.isLoading}
        dataSource={permissions}
        pagination={false}
        columns={[
          {
            title: t('datasources.settings.perm_col_user'),
            dataIndex: 'user_display_name',
            render: (_v, p) => (
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <Avatar name={p.user_display_name} size={24} />
                <div>
                  <div style={{ fontSize: 13 }}>{p.user_display_name}</div>
                  <div className="mono muted" style={{ fontSize: 11 }}>{p.user_email}</div>
                </div>
              </div>
            ),
          },
          {
            title: t('datasources.settings.perm_col_read'),
            width: 70,
            align: 'center',
            render: (_v, p) => <PermCell on={p.can_read} />,
          },
          {
            title: t('datasources.settings.perm_col_write'),
            width: 70,
            align: 'center',
            render: (_v, p) => <PermCell on={p.can_write} />,
          },
          {
            title: t('datasources.settings.perm_col_ddl'),
            width: 70,
            align: 'center',
            render: (_v, p) => <PermCell on={p.can_ddl} />,
          },
          {
            title: t('datasources.settings.perm_col_row_limit'),
            width: 110,
            render: (_v, p) =>
              p.row_limit_override !== null ? (
                <span className="mono">{fmtNum(p.row_limit_override)}</span>
              ) : (
                <span className="muted">default</span>
              ),
          },
          {
            title: t('datasources.settings.perm_col_schemas'),
            render: (_v, p) => (
              <span className="mono" style={{ fontSize: 12 }}>
                {p.allowed_schemas ? p.allowed_schemas.join(', ') : <span className="muted">all</span>}
              </span>
            ),
          },
          {
            title: t('datasources.settings.perm_col_expires'),
            width: 130,
            render: (_v, p) => (
              <span className="muted" style={{ fontSize: 12 }}>
                {p.expires_at ? fmtDate(p.expires_at) : t('datasources.settings.perm_never_expires')}
              </span>
            ),
          },
          {
            title: t('datasources.settings.perm_col_actions'),
            width: 100,
            align: 'right',
            render: (_v, p) => (
              <Button
                size="small"
                danger
                onClick={() => onRevoke(p)}
                disabled={revokeMutation.isPending}
              >
                {t('datasources.settings.perm_revoke')}
              </Button>
            ),
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
  const schemaQuery = useSchemaIntrospect(dsId);

  if (schemaQuery.isLoading) {
    return (
      <div style={{ padding: 28 }} className="muted">
        {t('datasources.settings.schema_loading')}
      </div>
    );
  }

  if (schemaQuery.isError || !schemaQuery.data) {
    return (
      <div style={{ padding: 28, color: 'var(--risk-high)' }}>
        {t('datasources.settings.schema_error')}
      </div>
    );
  }

  const tables = schemaQuery.data.schemas[0]?.tables ?? [];
  return (
    <div style={{ padding: 28 }}>
      <Table
        rowKey="name"
        size="middle"
        dataSource={tables}
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
