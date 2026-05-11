import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Skeleton,
  Switch,
  Table,
  Tabs,
  Tooltip,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  DeleteOutlined,
  LoadingOutlined,
  PlayCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { isAxiosError } from 'axios';
import dayjs, { type Dayjs } from 'dayjs';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { Avatar } from '@/components/common/Avatar';
import { EmptyState } from '@/components/common/EmptyState';
import { StatusPill } from '@/components/common/StatusPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { fmtDate, fmtNum, timeAgo } from '@/utils/dateFormat';
import { datasourceGrantErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { flattenSchemaToColumns } from '@/utils/schemaColumns';
import { userDisplay } from '@/utils/userDisplay';
import {
  datasourceKeys,
  deleteDatasource,
  getDatasource,
  getDatasourceSchema,
  grantPermission,
  listPermissions,
  revokePermission,
  testConnection,
  updateDatasource,
} from '@/api/datasources';
import { aiConfigKeys, listAiConfigs, listUsers, userKeys } from '@/api/admin';
import { listQueries, queryKeys, type QueryListFilters } from '@/api/queries';
import { listReviewPlans, reviewPlanKeys } from '@/api/reviewPlans';
import { useSchemaIntrospect } from '@/hooks/useSchemaIntrospect';
import type {
  CreatePermissionInput,
  Datasource,
  DatasourcePermission,
  UpdateDatasourceInput,
  User,
} from '@/types/api';

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

  const reviewPlansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: listReviewPlans,
  });

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
    review_plan_id: ds.review_plan_id ?? null,
    ai_analysis_enabled: ds.ai_analysis_enabled,
    ai_config_id: ds.ai_config_id ?? null,
    active: ds.active,
  };

  const aiConfigsQuery = useQuery({
    queryKey: aiConfigKeys.lists(),
    queryFn: listAiConfigs,
  });

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
    if (body.ai_analysis_enabled === false) {
      body.clear_ai_config = true;
      body.ai_config_id = null;
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
            <Form.Item
              label={t('datasources.settings.label_review_plan')}
              name="review_plan_id"
            >
              <Select
                allowClear
                loading={reviewPlansQuery.isLoading}
                placeholder={
                  reviewPlansQuery.isLoading
                    ? t('datasources.settings.review_plan_loading')
                    : t('datasources.settings.review_plan_placeholder')
                }
                options={(reviewPlansQuery.data ?? []).map((plan) => ({
                  value: plan.id,
                  label: plan.name,
                }))}
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
              shouldUpdate={(prev, next) => prev.ai_analysis_enabled !== next.ai_analysis_enabled}
              noStyle
            >
              {({ getFieldValue }) => (
                <Form.Item
                  label={t('datasources.settings.label_ai_config')}
                  name="ai_config_id"
                  rules={[
                    {
                      required: getFieldValue('ai_analysis_enabled') === true,
                      message: t('datasources.settings.ai_config_required'),
                    },
                  ]}
                  extra={
                    <a href="/admin/ai-configs/new" target="_blank" rel="noopener noreferrer">
                      {t('datasources.settings.add_ai_config_link')}
                    </a>
                  }
                >
                  <Select
                    allowClear
                    disabled={!getFieldValue('ai_analysis_enabled')}
                    placeholder={t('datasources.settings.ai_config_placeholder')}
                    options={(aiConfigsQuery.data ?? []).map((c) => ({
                      value: c.id,
                      label: `${c.name} · ${c.provider}`,
                    }))}
                  />
                </Form.Item>
              )}
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
  const [grantOpen, setGrantOpen] = useState(false);

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
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setGrantOpen(true)}
        >
          {t('datasources.settings.grant_access')}
        </Button>
      </div>
      <GrantAccessModal
        open={grantOpen}
        dsId={dsId}
        existingUserIds={permissions.map((p) => p.user_id)}
        onClose={() => setGrantOpen(false)}
      />
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
            render: (_v, p) => {
              const label = userDisplay(p.user_display_name, p.user_email);
              return (
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <Avatar name={label} size={24} />
                  <div>
                    <div style={{ fontSize: 13 }}>{label}</div>
                    <div className="mono muted" style={{ fontSize: 11 }}>{p.user_email}</div>
                  </div>
                </div>
              );
            },
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
            title: t('datasources.settings.perm_col_restricted_columns'),
            render: (_v, p) => {
              const cols = p.restricted_columns ?? [];
              if (cols.length === 0) {
                return <span className="muted">{t('datasources.settings.perm_no_restrictions')}</span>;
              }
              return (
                <Tooltip title={cols.join(', ')}>
                  <span style={{ fontSize: 12 }}>
                    {t('datasources.settings.perm_restricted_count', { count: cols.length })}
                  </span>
                </Tooltip>
              );
            },
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

interface GrantFormValues {
  user_id: string;
  can_read: boolean;
  can_write: boolean;
  can_ddl: boolean;
  row_limit_override?: number | null;
  allowed_schemas?: string[];
  allowed_tables?: string[];
  restricted_columns?: string[];
  expires_at?: Dayjs | null;
}

interface GrantAccessModalProps {
  open: boolean;
  dsId: string;
  existingUserIds: string[];
  onClose: () => void;
}

function GrantAccessModal({ open, dsId, existingUserIds, onClose }: GrantAccessModalProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<GrantFormValues>();
  const selectedSchemas = Form.useWatch('allowed_schemas', form);

  const usersQuery = useQuery({
    queryKey: userKeys.list({ size: 100 }),
    queryFn: () => listUsers({ size: 100 }),
    enabled: open,
  });

  const schemaQuery = useQuery({
    queryKey: datasourceKeys.schema(dsId),
    queryFn: () => getDatasourceSchema(dsId),
    enabled: open,
    staleTime: 5 * 60_000,
    retry: false,
  });

  useEffect(() => {
    if (open) {
      form.resetFields();
    }
  }, [open, form]);

  const taken = useMemo(() => new Set(existingUserIds), [existingUserIds]);
  const eligible: User[] = useMemo(
    () =>
      (usersQuery.data?.content ?? []).filter((u) => u.active && !taken.has(u.id)),
    [usersQuery.data, taken],
  );

  const schemaOptions = useMemo(
    () =>
      (schemaQuery.data?.schemas ?? []).map((s) => ({
        value: s.name,
        label: s.name,
      })),
    [schemaQuery.data],
  );

  const tableOptions = useMemo(() => {
    const schemas = schemaQuery.data?.schemas ?? [];
    const filter =
      selectedSchemas && selectedSchemas.length > 0
        ? new Set(selectedSchemas)
        : null;
    const seen = new Set<string>();
    const opts: { value: string; label: string }[] = [];
    for (const s of schemas) {
      if (filter && !filter.has(s.name)) continue;
      for (const tb of s.tables) {
        if (seen.has(tb.name)) continue;
        seen.add(tb.name);
        opts.push({ value: tb.name, label: tb.name });
      }
    }
    return opts;
  }, [schemaQuery.data, selectedSchemas]);

  const restrictedColumnOptions = useMemo(
    () => flattenSchemaToColumns(schemaQuery.data?.schemas ?? []),
    [schemaQuery.data],
  );

  const grantMutation = useMutation({
    mutationFn: (input: CreatePermissionInput) => grantPermission(dsId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: datasourceKeys.permissions(dsId) });
      message.success(t('datasources.settings.grant_success'));
      onClose();
    },
    onError: (err) => {
      showApiError(message, err, datasourceGrantErrorMessage);
    },
  });

  const onFinish = (values: GrantFormValues) => {
    if (!values.can_read && !values.can_write && !values.can_ddl) {
      message.error(t('datasources.settings.grant_at_least_one_perm'));
      return;
    }
    const payload: CreatePermissionInput = {
      user_id: values.user_id,
      can_read: values.can_read,
      can_write: values.can_write,
      can_ddl: values.can_ddl,
      row_limit_override:
        typeof values.row_limit_override === 'number' ? values.row_limit_override : null,
      allowed_schemas:
        values.allowed_schemas && values.allowed_schemas.length > 0
          ? values.allowed_schemas
          : null,
      allowed_tables:
        values.allowed_tables && values.allowed_tables.length > 0
          ? values.allowed_tables
          : null,
      restricted_columns:
        values.restricted_columns && values.restricted_columns.length > 0
          ? values.restricted_columns
          : null,
      expires_at: values.expires_at ? values.expires_at.toISOString() : null,
    };
    grantMutation.mutate(payload);
  };

  return (
    <Modal
      open={open}
      title={t('datasources.settings.grant_modal_title')}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('datasources.settings.grant_submit')}
      cancelText={t('common.cancel')}
      confirmLoading={grantMutation.isPending}
      destroyOnHidden
      width={520}
    >
      <Form<GrantFormValues>
        form={form}
        layout="vertical"
        initialValues={{
          can_read: true,
          can_write: false,
          can_ddl: false,
        }}
        onFinish={onFinish}
      >
        <Form.Item
          name="user_id"
          label={t('datasources.settings.grant_user_label')}
          rules={[
            { required: true, message: t('datasources.settings.grant_user_required') },
          ]}
        >
          <Select<string>
            showSearch
            optionFilterProp="label"
            placeholder={t('datasources.settings.grant_user_placeholder')}
            loading={usersQuery.isLoading}
            notFoundContent={
              usersQuery.isLoading
                ? t('datasources.settings.grant_user_loading')
                : t('datasources.settings.grant_user_empty')
            }
            options={eligible.map((u) => ({
              value: u.id,
              label: u.display_name ? `${u.display_name} (${u.email})` : u.email,
            }))}
          />
        </Form.Item>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }}>
          <Form.Item
            name="can_read"
            label={t('datasources.settings.grant_perm_read_label')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="can_write"
            label={t('datasources.settings.grant_perm_write_label')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="can_ddl"
            label={t('datasources.settings.grant_perm_ddl_label')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </div>
        <Form.Item
          name="row_limit_override"
          label={t('datasources.settings.grant_row_limit_label')}
          extra={t('datasources.settings.grant_row_limit_help')}
          rules={[
            {
              type: 'number',
              min: 1,
              message: t('datasources.settings.grant_row_limit_min'),
            },
          ]}
        >
          <InputNumber
            min={1}
            style={{ width: '100%' }}
            placeholder={t('datasources.settings.grant_row_limit_placeholder')}
          />
        </Form.Item>
        <Form.Item
          name="allowed_schemas"
          label={t('datasources.settings.grant_schemas_label')}
          extra={t('datasources.settings.grant_schemas_help')}
        >
          <Select
            mode="tags"
            tokenSeparators={[',', ' ']}
            placeholder={t('datasources.settings.grant_schemas_placeholder')}
            loading={schemaQuery.isLoading}
            options={schemaOptions}
            optionFilterProp="label"
          />
        </Form.Item>
        <Form.Item
          name="allowed_tables"
          label={t('datasources.settings.grant_tables_label')}
          extra={t('datasources.settings.grant_tables_help')}
        >
          <Select
            mode="tags"
            tokenSeparators={[',', ' ']}
            placeholder={t('datasources.settings.grant_tables_placeholder')}
            loading={schemaQuery.isLoading}
            options={tableOptions}
            optionFilterProp="label"
          />
        </Form.Item>
        <Form.Item
          name="restricted_columns"
          label={t('datasources.settings.grant_columns_label')}
          extra={t('datasources.settings.grant_columns_help')}
        >
          <Select
            mode="tags"
            tokenSeparators={[',', ' ']}
            placeholder={t('datasources.settings.grant_columns_placeholder')}
            loading={schemaQuery.isLoading}
            options={restrictedColumnOptions}
            optionFilterProp="label"
            allowClear
          />
        </Form.Item>
        <Form.Item
          name="expires_at"
          label={t('datasources.settings.grant_expires_label')}
          extra={t('datasources.settings.grant_expires_help')}
          rules={[
            {
              validator: (_rule, value: Dayjs | null | undefined) => {
                if (value && value.isBefore(dayjs())) {
                  return Promise.reject(
                    new Error(t('datasources.settings.grant_expires_future')),
                  );
                }
                return Promise.resolve();
              },
            },
          ]}
        >
          <DatePicker
            showTime
            style={{ width: '100%' }}
            placeholder={t('datasources.settings.grant_expires_placeholder')}
          />
        </Form.Item>
      </Form>
    </Modal>
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
  const filters: QueryListFilters = { datasource_id: dsId, size: 20 };
  const queriesQuery = useQuery({
    queryKey: queryKeys.list(filters),
    queryFn: () => listQueries(filters),
  });
  const rows = queriesQuery.data?.content ?? [];
  return (
    <div style={{ padding: 28 }}>
      {queriesQuery.isLoading ? (
        <Skeleton active paragraph={{ rows: 6 }} />
      ) : queriesQuery.isError ? (
        <EmptyState title={t('datasources.settings.activity_load_error')} />
      ) : rows.length === 0 ? (
        <EmptyState title={t('datasources.settings.activity_empty')} />
      ) : (
        <Table
          rowKey="id"
          size="middle"
          dataSource={rows}
          pagination={false}
          columns={[
            { title: t('datasources.settings.activity_col_id'), dataIndex: 'id', render: (v: string) => <span className="mono muted">{v}</span> },
            { title: t('datasources.settings.activity_col_type'), dataIndex: 'query_type', render: (v) => <QueryTypePill type={v} size="sm" /> },
            { title: t('datasources.settings.activity_col_status'), dataIndex: 'status', render: (v) => <StatusPill status={v} size="sm" /> },
            {
              title: t('datasources.settings.activity_col_submitter'),
              render: (_v, row) => userDisplay(row.submitted_by.display_name, row.submitted_by.email),
            },
            {
              title: t('datasources.settings.activity_col_when'),
              dataIndex: 'created_at',
              render: (v: string) => <span className="muted">{timeAgo(v)}</span>,
            },
          ]}
        />
      )}
    </div>
  );
}
