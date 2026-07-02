import { useState } from 'react';
import { App, Button, Form, Input, Modal, Select, Switch, Table, Tag } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import {
  apiConnectorKeys,
  createApiConnector,
  deleteApiConnector,
  listApiConnectors,
  testApiConnector,
} from '@/api/apiConnectors';
import { aiConfigKeys, listAiConfigs } from '@/api/admin';
import {
  API_AUTH_METHODS,
  API_PROTOCOLS,
  aiProviderLabel,
  apiAuthMethodLabel,
  apiProtocolLabel,
  enumOptions,
} from '@/utils/enumLabels';
import { Oauth2ConnectorFields } from '@/components/apigov/Oauth2ConnectorFields';
import { KeyValueEditor } from '@/components/apigov/KeyValueEditor';
import { pairsToRecord, type KeyValuePair } from '@/utils/apiRequestComposition';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  ApiConnector,
  ApiProtocol,
  ApiAuthMethod,
  CreateApiConnectorInput,
  Oauth2GrantType,
} from '@/types/api';

export default function ApiConnectorsListPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [headers, setHeaders] = useState<KeyValuePair[]>([]);
  const [form] = Form.useForm<CreateApiConnectorInput>();
  const authMethod = Form.useWatch('auth_method', form) as ApiAuthMethod | undefined;
  const grantType = Form.useWatch('oauth2_grant_type', form) as Oauth2GrantType | undefined;
  const aiAnalysisEnabled = Form.useWatch('ai_analysis_enabled', form) as boolean | undefined;
  const textToApiEnabled = Form.useWatch('text_to_api_enabled', form) as boolean | undefined;
  const aiRequired = aiAnalysisEnabled === true || textToApiEnabled === true;

  const connectorsQuery = useQuery({
    queryKey: apiConnectorKeys.list({ size: 100 }),
    queryFn: () => listApiConnectors({ size: 100 }),
  });

  const aiConfigsQuery = useQuery({
    queryKey: aiConfigKeys.lists(),
    queryFn: listAiConfigs,
  });

  const createMutation = useMutation({
    mutationFn: createApiConnector,
    onSuccess: (created) => {
      message.success(t('apiGov.connectors.created'));
      setOpen(false);
      form.resetFields();
      setHeaders([]);
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.lists() });
      navigate(`/api-connectors/${created.id}/settings`);
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  const testMutation = useMutation({
    mutationFn: testApiConnector,
    onSuccess: (result) =>
      result.success
        ? message.success(`${t('apiGov.connectors.testSuccess')}${result.message ? ` — ${result.message}` : ''}`)
        : message.error(`${t('apiGov.connectors.testFailed')}${result.message ? ` — ${result.message}` : ''}`),
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.connectors.testFailed'))),
  });

  const deleteMutation = useMutation({
    mutationFn: deleteApiConnector,
    onSuccess: () => {
      message.success(t('apiGov.connectors.deleted'));
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.lists() });
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  const columns = [
    {
      title: t('apiGov.connectors.name'),
      dataIndex: 'name',
      render: (name: string, row: ApiConnector) => (
        <a onClick={() => navigate(`/api-connectors/${row.id}/settings`)}>{name}</a>
      ),
    },
    {
      title: t('apiGov.connectors.protocol'),
      dataIndex: 'protocol',
      render: (p: ApiProtocol) => <Tag>{apiProtocolLabel(t, p)}</Tag>,
    },
    { title: t('apiGov.connectors.baseUrl'), dataIndex: 'base_url', ellipsis: true },
    {
      title: t('apiGov.connectors.authMethod'),
      dataIndex: 'auth_method',
      render: (a: ApiAuthMethod) => apiAuthMethodLabel(t, a),
    },
    {
      title: t('apiGov.connectors.schema'),
      dataIndex: 'schema_present',
      render: (present: boolean) =>
        present ? (
          <Tag color="green">{t('apiGov.connectors.schemaYes')}</Tag>
        ) : (
          <Tag>{t('apiGov.connectors.schemaNo')}</Tag>
        ),
    },
    {
      title: t('apiGov.connectors.active'),
      dataIndex: 'active',
      render: (active: boolean) =>
        active ? <Tag color="green">{t('apiGov.connectors.active')}</Tag> : <Tag>—</Tag>,
    },
    {
      title: t('apiGov.connectors.actions'),
      key: 'actions',
      render: (_: unknown, row: ApiConnector) => (
        <span style={{ display: 'flex', gap: 8 }}>
          <Button
            size="small"
            loading={testMutation.isPending && testMutation.variables === row.id}
            onClick={() => testMutation.mutate(row.id)}
          >
            {t('apiGov.connectors.test')}
          </Button>
          <Button
            size="small"
            danger
            onClick={() =>
              modal.confirm({
                title: t('apiGov.connectors.deleteConfirm'),
                okButtonProps: { danger: true },
                onOk: () => deleteMutation.mutateAsync(row.id),
              })
            }
          >
            {t('apiGov.connectors.delete')}
          </Button>
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('apiGov.connectors.title')}
        subtitle={t('apiGov.connectors.subtitle')}
        actions={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
            {t('apiGov.connectors.new')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        <Table<ApiConnector>
          rowKey="id"
          loading={connectorsQuery.isLoading}
          dataSource={connectorsQuery.data?.content ?? []}
          columns={columns}
          pagination={false}
          locale={{ emptyText: t('apiGov.connectors.empty') }}
        />
      </div>

      <Modal
        title={t('apiGov.connectors.new')}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={createMutation.isPending}
        okText={t('apiGov.connectors.save')}
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            protocol: 'REST',
            auth_method: 'NONE',
            oauth2_grant_type: 'CLIENT_CREDENTIALS',
            oauth2_client_auth: 'CLIENT_SECRET_BASIC',
            ai_analysis_enabled: true,
            text_to_api_enabled: false,
            ai_config_id: null,
          }}
          onFinish={(values) =>
            createMutation.mutate({ ...values, default_headers: pairsToRecord(headers) })
          }
        >
          <Form.Item
            name="name"
            label={t('apiGov.connectors.name')}
            rules={[
              { required: true, message: t('apiGov.connectors.nameRequired') },
              { min: 3, max: 255 },
            ]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="protocol"
            label={t('apiGov.connectors.protocol')}
            rules={[{ required: true, message: t('apiGov.connectors.protocolRequired') }]}
          >
            <Select options={enumOptions(API_PROTOCOLS, apiProtocolLabel, t)} />
          </Form.Item>
          <Form.Item
            name="base_url"
            label={t('apiGov.connectors.baseUrl')}
            rules={[
              { required: true, message: t('apiGov.connectors.baseUrlRequired') },
              { max: 2048 },
            ]}
          >
            <Input placeholder="https://api.example.com" />
          </Form.Item>
          <Form.Item name="auth_method" label={t('apiGov.connectors.authMethod')}>
            <Select options={enumOptions(API_AUTH_METHODS, apiAuthMethodLabel, t)} />
          </Form.Item>
          {authMethod === 'OAUTH2_CLIENT_CREDENTIALS' && (
            <Oauth2ConnectorFields grantType={grantType} />
          )}
          <Form.Item label={t('apiGov.settings.defaultHeaders')} extra={t('apiGov.settings.defaultHeadersHint')}>
            <KeyValueEditor pairs={headers} onChange={setHeaders} />
          </Form.Item>
          <Form.Item
            name="ai_analysis_enabled"
            label={t('apiGov.connectors.aiAnalysis')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="text_to_api_enabled"
            label={t('apiGov.connectors.textToApi')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="ai_config_id"
            label={t('apiGov.connectors.aiConfig')}
            dependencies={['ai_analysis_enabled', 'text_to_api_enabled']}
            rules={[{ required: aiRequired, message: t('apiGov.connectors.aiConfigRequired') }]}
            extra={
              <a href="/admin/ai-configs/new" target="_blank" rel="noopener noreferrer">
                {t('apiGov.connectors.addAiConfig')}
              </a>
            }
          >
            <Select
              allowClear
              disabled={!aiRequired}
              placeholder={t('apiGov.connectors.aiConfigPlaceholder')}
              options={(aiConfigsQuery.data ?? []).map((c) => ({
                value: c.id,
                label: `${c.name} · ${aiProviderLabel(t, c.provider)}`,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
