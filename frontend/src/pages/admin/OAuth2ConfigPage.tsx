import { useEffect, useMemo, useState } from 'react';
import { App, Button, Form, Input, Popconfirm, Select, Skeleton, Switch, Tabs } from 'antd';
import { CheckOutlined, DeleteOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  deleteOAuth2Config,
  listOAuth2Configs,
  oauth2ConfigKeys,
  updateOAuth2Config,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { OAuth2Config, OAuth2Provider, Role, UpdateOAuth2ConfigInput } from '@/types/api';

const MASK = '********';

const PROVIDERS: { provider: OAuth2Provider; label: string }[] = [
  { provider: 'GOOGLE', label: 'Google' },
  { provider: 'GITHUB', label: 'GitHub' },
  { provider: 'MICROSOFT', label: 'Microsoft' },
  { provider: 'GITLAB', label: 'GitLab' },
];

export function OAuth2ConfigPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [activeTab, setActiveTab] = useState<OAuth2Provider>('GOOGLE');

  const listQuery = useQuery({
    queryKey: oauth2ConfigKeys.list(),
    queryFn: listOAuth2Configs,
  });

  if (listQuery.isLoading) {
    return (
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 12 }} />
      </div>
    );
  }
  if (listQuery.isError) {
    return (
      <EmptyState
        title={t('admin.oauth2.load_error')}
        description={adminErrorMessage(listQuery.error)}
      />
    );
  }

  const configs: OAuth2Config[] = listQuery.data ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('admin.oauth2.title')} subtitle={t('admin.oauth2.subtitle')} />
      <div style={{ flex: 1, overflow: 'auto', padding: 28, maxWidth: 760 }}>
        <Tabs
          activeKey={activeTab}
          onChange={(key) => setActiveTab(key as OAuth2Provider)}
          items={PROVIDERS.map(({ provider, label }) => ({
            key: provider,
            label,
            children: (
              <ProviderForm
                provider={provider}
                config={
                  configs.find((c) => c.provider === provider) ??
                  defaultEmptyConfig(provider)
                }
                onSaved={() => {
                  void queryClient.invalidateQueries({ queryKey: oauth2ConfigKeys.all });
                }}
              />
            ),
          }))}
        />
      </div>
    </div>
  );
}

interface ProviderFormProps {
  provider: OAuth2Provider;
  config: OAuth2Config;
  onSaved: () => void;
}

interface OAuth2FormValues {
  client_id?: string;
  client_secret?: string;
  scopes_override?: string;
  tenant_id?: string;
  default_role: Role;
  active: boolean;
}

function ProviderForm({ provider, config, onSaved }: ProviderFormProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<OAuth2FormValues>();
  const initialValues = useMemo<OAuth2FormValues>(
    () => ({
      client_id: config.client_id ?? '',
      client_secret: config.client_secret ?? '',
      scopes_override: config.scopes_override ?? '',
      tenant_id: config.tenant_id ?? '',
      default_role: config.default_role,
      active: config.active,
    }),
    [config],
  );

  useEffect(() => {
    form.setFieldsValue(initialValues);
  }, [form, initialValues]);

  const saveMutation = useMutation({
    mutationFn: (payload: UpdateOAuth2ConfigInput) => updateOAuth2Config(provider, payload),
    onSuccess: () => {
      onSaved();
      message.success(t('admin.oauth2.save_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteOAuth2Config(provider),
    onSuccess: () => {
      onSaved();
      message.success(t('admin.oauth2.delete_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const onFinish = (values: OAuth2FormValues) => {
    saveMutation.mutate({
      client_id: blankToNull(values.client_id),
      client_secret:
        values.client_secret === MASK ? undefined : blankToNull(values.client_secret),
      scopes_override: blankToNull(values.scopes_override),
      tenant_id: blankToNull(values.tenant_id),
      default_role: values.default_role,
      active: values.active,
    });
  };

  return (
    <Form<OAuth2FormValues>
      form={form}
      layout="vertical"
      onFinish={onFinish}
      initialValues={initialValues}
    >
      <Form.Item
        name="client_id"
        label={t('admin.oauth2.label_client_id')}
        rules={[{ max: 512, message: t('validation.oauth2.client_id_max') }]}
      >
        <Input className="mono" maxLength={512} autoComplete="off" />
      </Form.Item>
      <Form.Item
        name="client_secret"
        label={t('admin.oauth2.label_client_secret')}
        extra={t('admin.oauth2.label_client_secret_help')}
        rules={[{ max: 2048, message: t('validation.oauth2.client_secret_max') }]}
      >
        <Input.Password className="mono" autoComplete="new-password" />
      </Form.Item>
      <Form.Item
        name="scopes_override"
        label={t('admin.oauth2.label_scopes')}
        extra={t('admin.oauth2.label_scopes_help')}
        rules={[{ max: 1024, message: t('validation.oauth2.scopes_max') }]}
      >
        <Input className="mono" maxLength={1024} placeholder="openid email profile" />
      </Form.Item>
      {provider === 'MICROSOFT' && (
        <Form.Item
          name="tenant_id"
          label={t('admin.oauth2.label_tenant_id')}
          extra={t('admin.oauth2.label_tenant_id_help')}
          rules={[{ max: 255, message: t('validation.oauth2.tenant_id_max') }]}
        >
          <Input className="mono" maxLength={255} placeholder="common" />
        </Form.Item>
      )}
      <Form.Item
        name="default_role"
        label={t('admin.oauth2.label_default_role')}
        rules={[{ required: true, message: t('validation.oauth2.default_role_required') }]}
      >
        <Select
          options={[
            { value: 'ADMIN', label: 'ADMIN' },
            { value: 'REVIEWER', label: 'REVIEWER' },
            { value: 'ANALYST', label: 'ANALYST' },
            { value: 'READONLY', label: 'READONLY' },
          ]}
        />
      </Form.Item>
      <Form.Item name="active" label={t('admin.oauth2.label_active')} valuePropName="checked">
        <Switch />
      </Form.Item>

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
          icon={<CheckOutlined />}
          htmlType="submit"
          loading={saveMutation.isPending}
        >
          {t('admin.oauth2.save_button')}
        </Button>
        {config.id !== null && (
          <Popconfirm
            title={t('admin.oauth2.delete_confirm')}
            okButtonProps={{ danger: true }}
            onConfirm={() => deleteMutation.mutate()}
          >
            <Button danger icon={<DeleteOutlined />} loading={deleteMutation.isPending}>
              {t('admin.oauth2.delete_button')}
            </Button>
          </Popconfirm>
        )}
      </div>
    </Form>
  );
}

function defaultEmptyConfig(provider: OAuth2Provider): OAuth2Config {
  return {
    id: null,
    organization_id: '',
    provider,
    client_id: null,
    client_secret: null,
    scopes_override: null,
    tenant_id: null,
    default_role: 'ANALYST',
    active: false,
    created_at: '',
    updated_at: '',
  };
}

function blankToNull(s: string | undefined | null): string | null {
  if (s === undefined || s === null) return null;
  return s.trim().length === 0 ? null : s.trim();
}

export default OAuth2ConfigPage;
