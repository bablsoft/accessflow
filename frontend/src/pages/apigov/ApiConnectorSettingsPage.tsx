import { useState } from 'react';
import { App, Button, Form, Input, InputNumber, Select, Switch, Table, Tabs, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import {
  apiConnectorKeys,
  deleteApiSchema,
  getApiConnector,
  grantApiConnectorPermission,
  listApiConnectorPermissions,
  listApiOperations,
  listApiSchemas,
  revokeApiConnectorPermission,
  updateApiConnector,
  uploadApiSchema,
} from '@/api/apiConnectors';
import { API_SCHEMA_TYPES, apiSchemaTypeLabel, enumOptions } from '@/utils/enumLabels';
import type {
  ApiConnectorPermission,
  ApiOperation,
  ApiSchema,
  ApiSchemaType,
  UpdateApiConnectorInput,
} from '@/types/api';

export default function ApiConnectorSettingsPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const connectorQuery = useQuery({
    queryKey: apiConnectorKeys.detail(id),
    queryFn: () => getApiConnector(id),
    enabled: !!id,
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={connectorQuery.data?.name ?? t('apiGov.settings.title')}
        subtitle={connectorQuery.data?.base_url}
        actions={<Button onClick={() => navigate('/api-connectors')}>{t('common.back')}</Button>}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        <Tabs
          items={[
            { key: 'config', label: t('apiGov.settings.tabConfig'), children: <ConfigTab connectorId={id} /> },
            { key: 'schema', label: t('apiGov.settings.tabSchema'), children: <SchemaTab connectorId={id} /> },
            { key: 'operations', label: t('apiGov.settings.tabOperations'), children: <OperationsTab connectorId={id} /> },
            { key: 'permissions', label: t('apiGov.settings.tabPermissions'), children: <PermissionsTab connectorId={id} /> },
          ]}
        />
      </div>
    </div>
  );
}

function ConfigTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const connectorQuery = useQuery({
    queryKey: apiConnectorKeys.detail(connectorId),
    queryFn: () => getApiConnector(connectorId),
  });
  const mutation = useMutation({
    mutationFn: (input: UpdateApiConnectorInput) => updateApiConnector(connectorId, input),
    onSuccess: () => {
      message.success(t('apiGov.connectors.updated'));
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.detail(connectorId) });
    },
    onError: () => message.error(t('apiGov.error')),
  });
  if (!connectorQuery.data) return null;
  const c = connectorQuery.data;
  return (
    <Form
      layout="vertical"
      style={{ maxWidth: 560 }}
      initialValues={{
        name: c.name,
        base_url: c.base_url,
        timeout_ms: c.timeout_ms,
        max_response_bytes: c.max_response_bytes,
        ai_analysis_enabled: c.ai_analysis_enabled,
        text_to_api_enabled: c.text_to_api_enabled,
        require_review_reads: c.require_review_reads,
        require_review_writes: c.require_review_writes,
        active: c.active,
      }}
      onFinish={(values) => mutation.mutate(values)}
    >
      <Form.Item name="name" label={t('apiGov.connectors.name')} rules={[{ required: true, min: 3, max: 255 }]}>
        <Input />
      </Form.Item>
      <Form.Item name="base_url" label={t('apiGov.connectors.baseUrl')} rules={[{ required: true, max: 2048 }]}>
        <Input />
      </Form.Item>
      <Form.Item name="timeout_ms" label={t('apiGov.connectors.timeoutMs')} rules={[{ type: 'number', min: 1 }]}>
        <InputNumber style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="max_response_bytes" label={t('apiGov.connectors.maxResponseBytes')} rules={[{ type: 'number', min: 1 }]}>
        <InputNumber style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="ai_analysis_enabled" label={t('apiGov.connectors.aiAnalysis')} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item name="text_to_api_enabled" label={t('apiGov.connectors.textToApi')} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item name="require_review_reads" label={t('apiGov.connectors.requireReviewReads')} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item name="require_review_writes" label={t('apiGov.connectors.requireReviewWrites')} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item name="active" label={t('apiGov.connectors.active')} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Button type="primary" htmlType="submit" loading={mutation.isPending}>
        {t('common.save')}
      </Button>
    </Form>
  );
}

function SchemaTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [schemaType, setSchemaType] = useState<ApiSchemaType>('OPENAPI');
  const [content, setContent] = useState('');
  const schemasQuery = useQuery({
    queryKey: apiConnectorKeys.schemas(connectorId),
    queryFn: () => listApiSchemas(connectorId),
  });
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.schemas(connectorId) });
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.operations(connectorId) });
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.detail(connectorId) });
  };
  const uploadMutation = useMutation({
    mutationFn: () => uploadApiSchema(connectorId, { schema_type: schemaType, raw_content: content }),
    onSuccess: () => {
      message.success(t('apiGov.settings.uploaded'));
      setContent('');
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });
  const deleteMutation = useMutation({
    mutationFn: (schemaId: string) => deleteApiSchema(connectorId, schemaId),
    onSuccess: () => {
      message.success(t('apiGov.settings.schemaDeleted'));
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 700 }}>
      <Select<ApiSchemaType>
        style={{ width: 240 }}
        value={schemaType}
        onChange={setSchemaType}
        options={enumOptions(API_SCHEMA_TYPES, apiSchemaTypeLabel, t)}
      />
      <Input.TextArea
        rows={8}
        value={content}
        onChange={(e) => setContent(e.target.value)}
        placeholder={t('apiGov.settings.schemaContent')}
      />
      <Button
        type="primary"
        style={{ alignSelf: 'flex-start' }}
        disabled={!content.trim()}
        loading={uploadMutation.isPending}
        onClick={() => uploadMutation.mutate()}
      >
        {t('apiGov.settings.uploadSchema')}
      </Button>
      <Table<ApiSchema>
        rowKey="id"
        size="small"
        pagination={false}
        loading={schemasQuery.isLoading}
        dataSource={schemasQuery.data ?? []}
        columns={[
          { title: t('apiGov.settings.schemaType'), dataIndex: 'schema_type', render: (s: ApiSchemaType) => apiSchemaTypeLabel(t, s) },
          { title: t('apiGov.settings.operations'), dataIndex: 'operation_count' },
          { title: '', key: 'a', render: (_: unknown, row: ApiSchema) => (
            <Button size="small" danger loading={deleteMutation.isPending} onClick={() => deleteMutation.mutate(row.id)}>
              {t('common.delete')}
            </Button>
          ) },
        ]}
      />
    </div>
  );
}

function OperationsTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const opsQuery = useQuery({
    queryKey: apiConnectorKeys.operations(connectorId),
    queryFn: () => listApiOperations(connectorId),
  });
  return (
    <Table<ApiOperation>
      rowKey="operation_id"
      size="small"
      pagination={false}
      loading={opsQuery.isLoading}
      dataSource={opsQuery.data ?? []}
      locale={{ emptyText: t('apiGov.settings.noOperations') }}
      columns={[
        { title: t('apiGov.settings.operationId'), dataIndex: 'operation_id' },
        { title: t('apiGov.settings.verb'), dataIndex: 'verb' },
        { title: t('apiGov.settings.path'), dataIndex: 'path' },
        {
          title: t('apiGov.settings.readWrite'),
          dataIndex: 'write',
          render: (write: boolean) =>
            write ? <Tag color="orange">{t('apiGov.settings.write')}</Tag> : <Tag color="green">{t('apiGov.settings.read')}</Tag>,
        },
      ]}
    />
  );
}

function PermissionsTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<{ user_id: string; can_read: boolean; can_write: boolean; can_break_glass: boolean }>();
  const permsQuery = useQuery({
    queryKey: apiConnectorKeys.permissions(connectorId),
    queryFn: () => listApiConnectorPermissions(connectorId),
  });
  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.permissions(connectorId) });
  const grantMutation = useMutation({
    mutationFn: (values: { user_id: string; can_read: boolean; can_write: boolean; can_break_glass: boolean }) =>
      grantApiConnectorPermission(connectorId, values),
    onSuccess: () => {
      message.success(t('apiGov.settings.granted'));
      form.resetFields();
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });
  const revokeMutation = useMutation({
    mutationFn: (permissionId: string) => revokeApiConnectorPermission(connectorId, permissionId),
    onSuccess: () => {
      message.success(t('apiGov.settings.revoked'));
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 760 }}>
      <Form
        form={form}
        layout="inline"
        initialValues={{ can_read: true, can_write: false, can_break_glass: false }}
        onFinish={(values) => grantMutation.mutate(values)}
      >
        <Form.Item name="user_id" rules={[{ required: true, message: t('apiGov.settings.userRequired') }]}>
          <Input placeholder={t('apiGov.settings.user')} style={{ width: 280 }} />
        </Form.Item>
        <Form.Item name="can_read" label={t('apiGov.settings.canRead')} valuePropName="checked">
          <Switch size="small" />
        </Form.Item>
        <Form.Item name="can_write" label={t('apiGov.settings.canWrite')} valuePropName="checked">
          <Switch size="small" />
        </Form.Item>
        <Form.Item name="can_break_glass" label={t('apiGov.settings.canBreakGlass')} valuePropName="checked">
          <Switch size="small" />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={grantMutation.isPending}>
          {t('apiGov.settings.grant')}
        </Button>
      </Form>
      <Table<ApiConnectorPermission>
        rowKey="id"
        size="small"
        pagination={false}
        loading={permsQuery.isLoading}
        dataSource={permsQuery.data ?? []}
        locale={{ emptyText: t('apiGov.settings.noPermissions') }}
        columns={[
          { title: t('apiGov.settings.user'), dataIndex: 'user_email', render: (e: string | null, r: ApiConnectorPermission) => e ?? r.user_id },
          { title: t('apiGov.settings.canRead'), dataIndex: 'can_read', render: (v: boolean) => (v ? '✓' : '—') },
          { title: t('apiGov.settings.canWrite'), dataIndex: 'can_write', render: (v: boolean) => (v ? '✓' : '—') },
          { title: t('apiGov.settings.canBreakGlass'), dataIndex: 'can_break_glass', render: (v: boolean) => (v ? '✓' : '—') },
          { title: '', key: 'a', render: (_: unknown, row: ApiConnectorPermission) => (
            <Button size="small" danger loading={revokeMutation.isPending} onClick={() => revokeMutation.mutate(row.id)}>
              {t('apiGov.settings.revoke')}
            </Button>
          ) },
        ]}
      />
    </div>
  );
}
