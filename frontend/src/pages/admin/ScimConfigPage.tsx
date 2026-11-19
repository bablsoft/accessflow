import { useEffect, useState } from 'react';
import { Alert, App, Button, Form, Input, Modal, Popconfirm, Select, Skeleton, Space, Switch, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { CheckOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { DataTable, FormActions, Page, PageBody, Section, UnsavedChangesGuard, useDirtyState } from '@/components/layout';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  createScimToken,
  getScimConfig,
  listScimTokens,
  revokeScimToken,
  scimConfigKeys,
  scimTokenKeys,
  updateScimConfig,
} from '@/api/admin';
import { apiBaseUrl } from '@/api/client';
import { adminErrorMessage } from '@/utils/apiErrors';
import { enumOptions, roleLabel } from '@/utils/enumLabels';
import { showApiError } from '@/utils/showApiError';
import type {
  CreatedScimToken,
  Role,
  ScimAttrDisplayName,
  ScimAttrEmail,
  ScimToken,
  UpdateScimConfigInput,
} from '@/types/api';

const ROLE_VALUES: readonly Role[] = ['ADMIN', 'REVIEWER', 'ANALYST', 'READONLY'] as const;
const ATTR_EMAIL_VALUES: readonly ScimAttrEmail[] = ['userName', 'emails.primary'] as const;
const ATTR_DISPLAY_NAME_VALUES: readonly ScimAttrDisplayName[] = [
  'displayName',
  'name.formatted',
  'userName',
] as const;

interface ScimFormValues {
  enabled: boolean;
  attr_email: ScimAttrEmail;
  attr_display_name: ScimAttrDisplayName;
  default_role: Role;
}

interface CreateTokenFormValues {
  name: string;
}

export function ScimConfigPage() {
  const { t, i18n } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<ScimFormValues>();
  const { dirty, markDirty, markClean } = useDirtyState();
  const [tokenForm] = Form.useForm<CreateTokenFormValues>();
  const [createOpen, setCreateOpen] = useState(false);
  const [issuedToken, setIssuedToken] = useState<CreatedScimToken | null>(null);

  const cfgQuery = useQuery({
    queryKey: scimConfigKeys.current(),
    queryFn: getScimConfig,
  });

  const tokensQuery = useQuery({
    queryKey: scimTokenKeys.list(),
    queryFn: listScimTokens,
  });

  useEffect(() => {
    if (cfgQuery.data) {
      form.setFieldsValue({
        enabled: cfgQuery.data.enabled,
        attr_email: cfgQuery.data.attr_email,
        attr_display_name: cfgQuery.data.attr_display_name,
        default_role: cfgQuery.data.default_role,
      });
    }
  }, [cfgQuery.data, form]);

  const saveMutation = useMutation({
    mutationFn: (payload: UpdateScimConfigInput) => updateScimConfig(payload),
    onSuccess: () => {
      markClean();
      void queryClient.invalidateQueries({ queryKey: scimConfigKeys.all });
      message.success(t('admin.scim.save_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const createTokenMutation = useMutation({
    mutationFn: createScimToken,
    onSuccess: (response) => {
      setIssuedToken(response);
      setCreateOpen(false);
      tokenForm.resetFields();
      void queryClient.invalidateQueries({ queryKey: scimTokenKeys.all });
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const revokeTokenMutation = useMutation({
    mutationFn: (id: string) => revokeScimToken(id),
    onSuccess: () => {
      message.success(t('admin.scim.token_revoked'));
      void queryClient.invalidateQueries({ queryKey: scimTokenKeys.all });
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const onFinish = (values: ScimFormValues) => {
    saveMutation.mutate({
      enabled: values.enabled,
      attr_email: values.attr_email,
      attr_display_name: values.attr_display_name,
      default_role: values.default_role,
    });
  };

  const dateFormatter = new Intl.DateTimeFormat(i18n.language, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
  const fmtDate = (value: string | null) => (value ? dateFormatter.format(new Date(value)) : '—');

  // The IdP calls the BACKEND, not the frontend — always the API base URL here, never
  // window.location.origin (wrong host → silent fall-through to the SPA; see the same
  // warning in OAuth2ConfigPage.callbackUrlFor).
  const baseUrl = `${apiBaseUrl().replace(/\/+$/, '')}/scim/v2`;

  const tokenColumns: ColumnsType<ScimToken> = [
    {
      title: t('admin.scim.token_column_name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => <strong>{name}</strong>,
    },
    {
      title: t('admin.scim.token_column_prefix'),
      dataIndex: 'token_prefix',
      key: 'token_prefix',
      render: (prefix: string) => <Typography.Text code>{prefix}…</Typography.Text>,
    },
    {
      title: t('admin.scim.token_column_created_at'),
      dataIndex: 'created_at',
      key: 'created_at',
      render: fmtDate,
    },
    {
      title: t('admin.scim.token_column_last_used_at'),
      dataIndex: 'last_used_at',
      key: 'last_used_at',
      render: fmtDate,
    },
    {
      title: t('admin.scim.token_column_status'),
      key: 'status',
      render: (_, token) =>
        token.revoked_at ? t('admin.scim.token_status_revoked') : t('admin.scim.token_status_active'),
    },
    {
      title: '',
      key: 'actions',
      width: 120,
      render: (_, token) =>
        token.revoked_at ? null : (
          <Popconfirm
            title={t('admin.scim.token_revoke_confirm', { name: token.name })}
            okText={t('admin.scim.token_revoke')}
            cancelText={t('common.cancel')}
            onConfirm={() => revokeTokenMutation.mutate(token.id)}
          >
            <Button
              type="link"
              danger
              size="small"
              aria-label={t('admin.scim.token_revoke_aria', { name: token.name })}
              loading={revokeTokenMutation.isPending && revokeTokenMutation.variables === token.id}
            >
              {t('admin.scim.token_revoke')}
            </Button>
          </Popconfirm>
        ),
    },
  ];

  if (cfgQuery.isLoading) {
    return (
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 12 }} />
      </div>
    );
  }
  if (cfgQuery.isError) {
    return (
      <EmptyState
        title={t('admin.scim.load_error')}
        description={adminErrorMessage(cfgQuery.error)}
      />
    );
  }

  return (
    <Page>
      <PageHeader
        docsAnchor="cfg-scim"
        title={t('admin.scim.title')}
        subtitle={t('admin.scim.subtitle')}
      />
      <PageBody width="form">
        <Form<ScimFormValues> form={form} layout="vertical" onFinish={onFinish} onValuesChange={markDirty}>
          <Section title={t('admin.scim.section_endpoint')}>
            <Form.Item label={t('admin.scim.label_base_url')}>
              <Typography.Paragraph
                copyable={{ text: baseUrl }}
                code
                style={{ marginBottom: 0 }}
              >
                {baseUrl}
              </Typography.Paragraph>
            </Form.Item>
            <Form.Item
              name="enabled"
              label={t('admin.scim.label_enabled')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          </Section>

          <Section title={t('admin.scim.section_mapping')}>
            <Grid>
              <Form.Item
                name="attr_email"
                label={t('admin.scim.label_attr_email')}
                rules={[{ required: true }]}
              >
                {/* Options are literal SCIM wire attribute paths (RFC 7643), not
                    translatable enum labels — rendered verbatim on purpose. */}
                <Select
                  options={ATTR_EMAIL_VALUES.map((value) => ({ value, label: value }))}
                />
              </Form.Item>
              <Form.Item
                name="attr_display_name"
                label={t('admin.scim.label_attr_display_name')}
                rules={[{ required: true }]}
              >
                <Select
                  options={ATTR_DISPLAY_NAME_VALUES.map((value) => ({ value, label: value }))}
                />
              </Form.Item>
              <Form.Item
                name="default_role"
                label={t('admin.scim.label_default_role')}
                rules={[{ required: true }]}
              >
                <Select options={enumOptions(ROLE_VALUES, roleLabel, t)} />
              </Form.Item>
            </Grid>
          </Section>

          <UnsavedChangesGuard when={dirty} />
          <FormActions dirty={dirty}>
            <Button
              type="primary"
              icon={<CheckOutlined />}
              htmlType="submit"
              loading={saveMutation.isPending}
            >
              {t('admin.scim.save_button')}
            </Button>
          </FormActions>
        </Form>

        <div style={{ marginTop: 40 }}>
          <Section title={t('admin.scim.section_tokens')}>
            <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}
              >
                <Typography.Text type="secondary">
                  {t('admin.scim.tokens_description')}
                </Typography.Text>
                <Button type="primary" onClick={() => setCreateOpen(true)}>
                  {t('admin.scim.token_create')}
                </Button>
              </div>

              {tokensQuery.isLoading ? (
                <Skeleton active />
              ) : tokensQuery.isError ? (
                <Alert
                  type="error"
                  showIcon
                  title={t('admin.scim.tokens_load_error')}
                  description={adminErrorMessage(tokensQuery.error)}
                />
              ) : tokensQuery.data && tokensQuery.data.length > 0 ? (
                <DataTable<ScimToken>
                  dataSource={tokensQuery.data}
                  rowKey="id"
                  columns={tokenColumns}
                  pagination={false}
                  size="small"
                  scroll={{ x: 'max-content' }}
                  aria-label={t('admin.scim.section_tokens')}
                />
              ) : (
                <EmptyState size="sm" title={t('admin.scim.tokens_empty')} />
              )}
            </Space>
          </Section>
        </div>
      </PageBody>

      <Modal
        title={t('admin.scim.token_create_title')}
        open={createOpen}
        onCancel={() => {
          setCreateOpen(false);
          tokenForm.resetFields();
        }}
        onOk={() => tokenForm.submit()}
        confirmLoading={createTokenMutation.isPending}
        okText={t('admin.scim.token_create')}
        cancelText={t('common.cancel')}
        destroyOnHidden
      >
        <Form<CreateTokenFormValues>
          form={tokenForm}
          layout="vertical"
          onFinish={(values) => createTokenMutation.mutate({ name: values.name })}
        >
          <Form.Item
            name="name"
            label={t('admin.scim.token_name_label')}
            rules={[
              { required: true, whitespace: true, message: t('admin.scim.token_name_required') },
              { max: 100, message: t('admin.scim.token_name_size') },
            ]}
          >
            <Input placeholder={t('admin.scim.token_name_placeholder')} autoFocus />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('admin.scim.token_issued_title')}
        open={Boolean(issuedToken)}
        onCancel={() => setIssuedToken(null)}
        footer={[
          <Button key="close" type="primary" onClick={() => setIssuedToken(null)}>
            {t('common.close')}
          </Button>,
        ]}
        destroyOnHidden
      >
        {issuedToken && (
          <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
            <Alert type="warning" showIcon title={t('admin.scim.token_copy_once_warning')} />
            <Typography.Text strong>{t('admin.scim.token_name_label')}</Typography.Text>
            <Typography.Text>{issuedToken.token.name}</Typography.Text>
            <Typography.Text strong>{t('admin.scim.token_raw_label')}</Typography.Text>
            <Typography.Paragraph
              copyable={{ text: issuedToken.raw_token }}
              code
              style={{ wordBreak: 'break-all' }}
            >
              {issuedToken.raw_token}
            </Typography.Paragraph>
          </Space>
        )}
      </Modal>
    </Page>
  );
}


function Grid({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>{children}</div>
  );
}

export default ScimConfigPage;
