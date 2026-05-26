import { useEffect, useMemo, useState } from 'react';
import { Alert, App, Button, Form, Input, Popconfirm, Select, Skeleton, Switch, Tabs, Tooltip } from 'antd';
import { CheckOutlined, CopyOutlined, DeleteOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Trans, useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  deleteOAuth2Config,
  listOAuth2Configs,
  oauth2ConfigKeys,
  updateOAuth2Config,
} from '@/api/admin';
import { apiBaseUrl } from '@/api/client';
import { adminErrorMessage } from '@/utils/apiErrors';
import { enumOptions, roleLabel } from '@/utils/enumLabels';
import { showApiError } from '@/utils/showApiError';
import type { OAuth2Config, OAuth2Provider, Role, UpdateOAuth2ConfigInput } from '@/types/api';

const ROLE_VALUES: readonly Role[] = ['ADMIN', 'REVIEWER', 'ANALYST', 'READONLY'] as const;

const MASK = '********';

const PROVIDERS: { provider: OAuth2Provider; label: string }[] = [
  { provider: 'GOOGLE', label: 'Google' },
  { provider: 'GITHUB', label: 'GitHub' },
  { provider: 'MICROSOFT', label: 'Microsoft' },
  { provider: 'GITLAB', label: 'GitLab' },
  { provider: 'GITHUB_ENTERPRISE', label: 'GitHub Enterprise' },
  { provider: 'GITLAB_ENTERPRISE', label: 'GitLab (self-managed)' },
  { provider: 'OIDC', label: 'OpenID Connect' },
];

// Upstream console links are provider-specific; OIDC is operator-defined and has
// no canonical console URL, so it's intentionally omitted here.
const PROVIDER_CONSOLE_URLS: Partial<Record<OAuth2Provider, string>> = {
  GOOGLE: 'https://console.cloud.google.com/apis/credentials',
  GITHUB: 'https://github.com/settings/developers',
  MICROSOFT: 'https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade',
  GITLAB: 'https://gitlab.com/-/profile/applications',
};

function callbackUrlFor(provider: OAuth2Provider): string {
  // The provider redirects to the BACKEND, not the frontend — so we always use the
  // API base URL here, not window.location.origin. Wrong host → silent fall-through to
  // the SPA, which is the exact bug that produced "click → page refresh → /auth/refresh 401".
  return `${apiBaseUrl().replace(/\/+$/, '')}/api/v1/auth/oauth2/callback/${provider.toLowerCase()}`;
}

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
  display_name?: string;
  authorization_uri?: string;
  token_uri?: string;
  user_info_uri?: string;
  jwk_set_uri?: string;
  issuer_uri?: string;
  user_name_attribute?: string;
  email_attribute?: string;
  email_verified_attribute?: string;
  display_name_attribute?: string;
  groups_attribute?: string;
  base_url?: string;
  allowed_organizations?: string[];
  allowed_email_domains?: string[];
  default_role: Role;
  active: boolean;
}

function isEnterpriseProvider(provider: OAuth2Provider): boolean {
  return provider === 'GITHUB_ENTERPRISE' || provider === 'GITLAB_ENTERPRISE';
}

const GITHUB_READ_ORG_SCOPE = 'read:org';

function scopesContain(scopes: string | undefined | null, token: string): boolean {
  if (!scopes) return false;
  return scopes.split(/\s+/).includes(token);
}

function ProviderForm({ provider, config, onSaved }: ProviderFormProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<OAuth2FormValues>();
  const callbackUrl = useMemo(() => callbackUrlFor(provider), [provider]);
  const onCopyCallback = async () => {
    try {
      await navigator.clipboard.writeText(callbackUrl);
      message.success(t('admin.oauth2.callback_url_copied'));
    } catch {
      // Clipboard may be unavailable in non-secure contexts; the URL is still on screen.
    }
  };
  const initialValues = useMemo<OAuth2FormValues>(
    () => ({
      client_id: config.client_id ?? '',
      client_secret: config.client_secret ?? '',
      scopes_override: config.scopes_override ?? '',
      tenant_id: config.tenant_id ?? '',
      display_name: config.display_name ?? '',
      authorization_uri: config.authorization_uri ?? '',
      token_uri: config.token_uri ?? '',
      user_info_uri: config.user_info_uri ?? '',
      jwk_set_uri: config.jwk_set_uri ?? '',
      issuer_uri: config.issuer_uri ?? '',
      user_name_attribute: config.user_name_attribute ?? '',
      email_attribute: config.email_attribute ?? '',
      email_verified_attribute: config.email_verified_attribute ?? '',
      display_name_attribute: config.display_name_attribute ?? '',
      groups_attribute: config.groups_attribute ?? '',
      base_url: config.base_url ?? '',
      allowed_organizations: config.allowed_organizations ?? [],
      allowed_email_domains: config.allowed_email_domains ?? [],
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
      display_name: blankToNull(values.display_name),
      authorization_uri: blankToNull(values.authorization_uri),
      token_uri: blankToNull(values.token_uri),
      user_info_uri: blankToNull(values.user_info_uri),
      jwk_set_uri: blankToNull(values.jwk_set_uri),
      issuer_uri: blankToNull(values.issuer_uri),
      user_name_attribute: blankToNull(values.user_name_attribute),
      email_attribute: blankToNull(values.email_attribute),
      email_verified_attribute: blankToNull(values.email_verified_attribute),
      display_name_attribute: blankToNull(values.display_name_attribute),
      groups_attribute: blankToNull(values.groups_attribute),
      base_url: blankToNull(values.base_url),
      allowed_organizations: normalizeOrganizations(values.allowed_organizations),
      allowed_email_domains: normalizeDomains(values.allowed_email_domains),
      default_role: values.default_role,
      active: values.active,
    });
  };

  return (
    <Form<OAuth2FormValues>
      form={form}
      // Distinct form name per provider tab so input element IDs (Form.Item
      // generates `<form-name>_<item-name>`) don't collide once Ant Design
      // Tabs keeps previously-activated panes mounted in the DOM. Without
      // this, label-for associations resolve to the wrong tab's input after
      // the user (or an e2e test) switches tabs.
      name={`oauth2-${provider.toLowerCase()}`}
      layout="vertical"
      onFinish={onFinish}
      initialValues={initialValues}
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={t('admin.oauth2.setup_intro_title', { provider: PROVIDERS.find((p) => p.provider === provider)?.label })}
        description={
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div>
              <Trans
                i18nKey={`admin.oauth2.setup_intro.${provider.toLowerCase()}`}
                components={
                  PROVIDER_CONSOLE_URLS[provider]
                    ? {
                        console: (
                          <a
                            href={PROVIDER_CONSOLE_URLS[provider]}
                            target="_blank"
                            rel="noopener noreferrer"
                          />
                        ),
                      }
                    : {}
                }
              />
            </div>
            <div>
              <div style={{ fontWeight: 600, fontSize: 12, marginBottom: 4 }}>
                {t('admin.oauth2.callback_url_label')}
              </div>
              <Input
                value={callbackUrl}
                readOnly
                className="mono"
                onFocus={(e) => e.currentTarget.select()}
                addonAfter={
                  <Button
                    type="text"
                    size="small"
                    icon={<CopyOutlined />}
                    onClick={onCopyCallback}
                    aria-label={t('admin.oauth2.callback_url_copy')}
                  />
                }
              />
              <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                {t('admin.oauth2.callback_url_help')}
              </div>
            </div>
          </div>
        }
      />
      <Form.Item
        name="client_id"
        label={
          <span>
            {t('admin.oauth2.label_client_id')}{' '}
            <Tooltip title={t(`admin.oauth2.client_id_tooltip.${provider.toLowerCase()}`)}>
              <QuestionCircleOutlined style={{ color: 'var(--fg-muted)' }} />
            </Tooltip>
          </span>
        }
        rules={[{ max: 512, message: t('validation.oauth2.client_id_max') }]}
      >
        <Input className="mono" maxLength={512} autoComplete="off" />
      </Form.Item>
      <Form.Item
        name="client_secret"
        label={
          <span>
            {t('admin.oauth2.label_client_secret')}{' '}
            <Tooltip title={t(`admin.oauth2.client_secret_tooltip.${provider.toLowerCase()}`)}>
              <QuestionCircleOutlined style={{ color: 'var(--fg-muted)' }} />
            </Tooltip>
          </span>
        }
        extra={t('admin.oauth2.label_client_secret_help')}
        rules={[{ max: 2048, message: t('validation.oauth2.client_secret_max') }]}
      >
        <Input.Password className="mono" autoComplete="new-password" />
      </Form.Item>
      <Form.Item
        name="scopes_override"
        label={
          <span>
            {t('admin.oauth2.label_scopes')}{' '}
            <Tooltip title={t('admin.oauth2.scopes_tooltip', { defaults: defaultScopesFor(provider) })}>
              <QuestionCircleOutlined style={{ color: 'var(--fg-muted)' }} />
            </Tooltip>
          </span>
        }
        extra={t('admin.oauth2.label_scopes_help')}
        rules={[{ max: 1024, message: t('validation.oauth2.scopes_max') }]}
      >
        <Input className="mono" maxLength={1024} placeholder={defaultScopesFor(provider)} />
      </Form.Item>
      {isEnterpriseProvider(provider) && (
        <Form.Item
          name="base_url"
          label={
            <span>
              {t('admin.oauth2.label_base_url')}{' '}
              <Tooltip title={t(`admin.oauth2.base_url_tooltip.${provider.toLowerCase()}`)}>
                <QuestionCircleOutlined style={{ color: 'var(--fg-muted)' }} />
              </Tooltip>
            </span>
          }
          extra={t('admin.oauth2.label_base_url_help')}
          rules={[
            { required: true, message: t('validation.oauth2.base_url_required') },
            { type: 'url', message: t('validation.oauth2.base_url_invalid') },
            { max: 2048, message: t('validation.oauth2.uri_max') },
          ]}
        >
          <Input
            className="mono"
            maxLength={2048}
            placeholder={
              provider === 'GITHUB_ENTERPRISE'
                ? 'https://github.acme.corp'
                : 'https://gitlab.acme.corp'
            }
          />
        </Form.Item>
      )}
      {provider === 'MICROSOFT' && (
        <Form.Item
          name="tenant_id"
          label={
            <span>
              {t('admin.oauth2.label_tenant_id')}{' '}
              <Tooltip title={t('admin.oauth2.tenant_id_tooltip')}>
                <QuestionCircleOutlined style={{ color: 'var(--fg-muted)' }} />
              </Tooltip>
            </span>
          }
          extra={t('admin.oauth2.label_tenant_id_help')}
          rules={[{ max: 255, message: t('validation.oauth2.tenant_id_max') }]}
        >
          <Input className="mono" maxLength={255} placeholder="common" />
        </Form.Item>
      )}
      {provider === 'OIDC' && (
        <>
          <Form.Item
            name="display_name"
            label={t('admin.oauth2.label_display_name')}
            extra={t('admin.oauth2.label_display_name_help')}
            rules={[
              {
                required: true,
                message: t('validation.oauth2.display_name_required'),
              },
              { max: 255, message: t('validation.oauth2.display_name_max') },
            ]}
          >
            <Input maxLength={255} placeholder="Keycloak" />
          </Form.Item>
          <Form.Item
            name="authorization_uri"
            label={t('admin.oauth2.label_authorization_uri')}
            rules={[
              { required: true, message: t('validation.oauth2.authorization_uri_required') },
              { type: 'url', message: t('validation.oauth2.uri_invalid') },
              { max: 2048, message: t('validation.oauth2.uri_max') },
            ]}
          >
            <Input className="mono" placeholder="https://idp.example.com/oauth/authorize" />
          </Form.Item>
          <Form.Item
            name="token_uri"
            label={t('admin.oauth2.label_token_uri')}
            rules={[
              { required: true, message: t('validation.oauth2.token_uri_required') },
              { type: 'url', message: t('validation.oauth2.uri_invalid') },
              { max: 2048, message: t('validation.oauth2.uri_max') },
            ]}
          >
            <Input className="mono" placeholder="https://idp.example.com/oauth/token" />
          </Form.Item>
          <Form.Item
            name="user_info_uri"
            label={t('admin.oauth2.label_user_info_uri')}
            rules={[
              { required: true, message: t('validation.oauth2.user_info_uri_required') },
              { type: 'url', message: t('validation.oauth2.uri_invalid') },
              { max: 2048, message: t('validation.oauth2.uri_max') },
            ]}
          >
            <Input className="mono" placeholder="https://idp.example.com/userinfo" />
          </Form.Item>
          <Form.Item
            name="jwk_set_uri"
            label={t('admin.oauth2.label_jwk_set_uri')}
            rules={[
              { required: true, message: t('validation.oauth2.jwk_set_uri_required') },
              { type: 'url', message: t('validation.oauth2.uri_invalid') },
              { max: 2048, message: t('validation.oauth2.uri_max') },
            ]}
          >
            <Input className="mono" placeholder="https://idp.example.com/jwks" />
          </Form.Item>
          <Form.Item
            name="issuer_uri"
            label={t('admin.oauth2.label_issuer_uri')}
            rules={[
              { required: true, message: t('validation.oauth2.issuer_uri_required') },
              { type: 'url', message: t('validation.oauth2.uri_invalid') },
              { max: 2048, message: t('validation.oauth2.uri_max') },
            ]}
          >
            <Input className="mono" placeholder="https://idp.example.com" />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('admin.oauth2.help_oidc_attribute_defaults')}
          />
          <Form.Item
            name="user_name_attribute"
            label={t('admin.oauth2.label_user_name_attribute')}
            rules={[{ max: 255, message: t('validation.oauth2.attribute_max') }]}
          >
            <Input className="mono" maxLength={255} placeholder="sub" />
          </Form.Item>
          <Form.Item
            name="email_attribute"
            label={t('admin.oauth2.label_email_attribute')}
            rules={[{ max: 255, message: t('validation.oauth2.attribute_max') }]}
          >
            <Input className="mono" maxLength={255} placeholder="email" />
          </Form.Item>
          <Form.Item
            name="email_verified_attribute"
            label={t('admin.oauth2.label_email_verified_attribute')}
            rules={[{ max: 255, message: t('validation.oauth2.attribute_max') }]}
          >
            <Input className="mono" maxLength={255} placeholder="email_verified" />
          </Form.Item>
          <Form.Item
            name="display_name_attribute"
            label={t('admin.oauth2.label_display_name_attribute')}
            rules={[{ max: 255, message: t('validation.oauth2.attribute_max') }]}
          >
            <Input className="mono" maxLength={255} placeholder="name" />
          </Form.Item>
          <Form.Item
            name="groups_attribute"
            label={t('admin.oauth2.label_groups_attribute')}
            extra={t('admin.oauth2.label_groups_attribute_help')}
            rules={[{ max: 255, message: t('validation.oauth2.attribute_max') }]}
          >
            <Input className="mono" maxLength={255} placeholder="groups" />
          </Form.Item>
        </>
      )}
      <Form.Item
        name="allowed_organizations"
        label={
          <span>
            {t('admin.oauth2.label_allowed_organizations')}{' '}
            <Tooltip
              title={t(`admin.oauth2.allowed_orgs_help.${provider.toLowerCase()}`)}
            >
              <QuestionCircleOutlined style={{ color: 'var(--fg-muted)' }} />
            </Tooltip>
          </span>
        }
        extra={t(`admin.oauth2.allowed_orgs_help.${provider.toLowerCase()}`)}
        rules={[
          {
            validator: (_rule, value: string[] | undefined) =>
              validateAllowlist(value, t),
          },
        ]}
      >
        <Select
          mode="tags"
          tokenSeparators={[',', ' ']}
          placeholder={t(`admin.oauth2.allowed_orgs_placeholder.${provider.toLowerCase()}`)}
          maxTagCount="responsive"
          disabled={provider === 'GOOGLE'}
          aria-label={t('admin.oauth2.label_allowed_organizations')}
        />
      </Form.Item>
      <Form.Item
        name="allowed_email_domains"
        label={
          <span>
            {t('admin.oauth2.label_allowed_email_domains')}{' '}
            <Tooltip title={t('admin.oauth2.allowed_domains_help')}>
              <QuestionCircleOutlined style={{ color: 'var(--fg-muted)' }} />
            </Tooltip>
          </span>
        }
        extra={t('admin.oauth2.allowed_domains_help')}
        rules={[
          {
            validator: (_rule, value: string[] | undefined) =>
              validateAllowlist(value, t),
          },
        ]}
      >
        <Select
          mode="tags"
          tokenSeparators={[',', ' ']}
          placeholder="example.com"
          maxTagCount="responsive"
          aria-label={t('admin.oauth2.label_allowed_email_domains')}
        />
      </Form.Item>
      {(provider === 'GITHUB' || provider === 'GITHUB_ENTERPRISE') && (
        <Form.Item shouldUpdate noStyle>
          {({ getFieldValue }) => {
            const orgs = (getFieldValue('allowed_organizations') ?? []) as string[];
            const scopes = (getFieldValue('scopes_override') ?? '') as string;
            const needsWarning =
              orgs.filter((o) => o && o.trim().length > 0).length > 0 &&
              !scopesContain(scopes, GITHUB_READ_ORG_SCOPE);
            if (!needsWarning) return null;
            return (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 16 }}
                message={t('admin.oauth2.github_read_org_scope_warning_title')}
                description={t('admin.oauth2.github_read_org_scope_warning')}
              />
            );
          }}
        </Form.Item>
      )}
      <Form.Item
        name="default_role"
        label={t('admin.oauth2.label_default_role')}
        rules={[{ required: true, message: t('validation.oauth2.default_role_required') }]}
      >
        <Select options={enumOptions(ROLE_VALUES, roleLabel, t)} />
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
    display_name: null,
    authorization_uri: null,
    token_uri: null,
    user_info_uri: null,
    jwk_set_uri: null,
    issuer_uri: null,
    user_name_attribute: null,
    email_attribute: null,
    email_verified_attribute: null,
    display_name_attribute: null,
    groups_attribute: null,
    base_url: null,
    allowed_organizations: null,
    allowed_email_domains: null,
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

function normalizeOrganizations(values: string[] | undefined): string[] {
  if (!values) return [];
  return values
    .map((v) => (v ?? '').trim())
    .filter((v) => v.length > 0);
}

function normalizeDomains(values: string[] | undefined): string[] {
  if (!values) return [];
  return values
    .map((v) => (v ?? '').trim().toLowerCase())
    .filter((v) => v.length > 0);
}

function validateAllowlist(
  values: string[] | undefined,
  t: (key: string) => string,
): Promise<void> {
  if (!values || values.length === 0) return Promise.resolve();
  if (values.length > 100) {
    return Promise.reject(new Error(t('validation.oauth2.allowed_max')));
  }
  for (const v of values) {
    const trimmed = (v ?? '').trim();
    if (trimmed.length === 0) {
      return Promise.reject(new Error(t('validation.oauth2.allowed_entry_blank')));
    }
    if (trimmed.length > 255) {
      return Promise.reject(new Error(t('validation.oauth2.allowed_entry_max')));
    }
  }
  return Promise.resolve();
}

function defaultScopesFor(provider: OAuth2Provider): string {
  switch (provider) {
    case 'GITHUB':
    case 'GITHUB_ENTERPRISE':
      return 'read:user user:email';
    case 'GOOGLE':
    case 'MICROSOFT':
    case 'GITLAB':
    case 'GITLAB_ENTERPRISE':
    case 'OIDC':
    default:
      return 'openid email profile';
  }
}

export default OAuth2ConfigPage;
