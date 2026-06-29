import { Form, Input, Select, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  OAUTH2_CLIENT_AUTHS,
  OAUTH2_GRANT_TYPES,
  enumOptions,
  oauth2ClientAuthLabel,
  oauth2GrantTypeLabel,
} from '@/utils/enumLabels';
import type { Oauth2GrantType } from '@/types/api';

interface Oauth2ConnectorFieldsProps {
  /** Selected grant type (drives which credential fields are shown). */
  grantType?: Oauth2GrantType;
  /** Whether a client secret is already stored (write-only field; blank keeps it). */
  clientSecretConfigured?: boolean;
  /** Whether a refresh token is already stored. */
  refreshTokenConfigured?: boolean;
  /** Whether a resource-owner password is already stored. */
  passwordConfigured?: boolean;
}

/**
 * OAuth2 token-sourcing fields shown when a connector's auth method is
 * {@code OAUTH2_CLIENT_CREDENTIALS}. Field names are snake_case to match the API payload. Secrets are
 * write-only: a "Configured" tag indicates a stored value and leaving the field blank keeps it.
 */
export function Oauth2ConnectorFields({
  grantType,
  clientSecretConfigured,
  refreshTokenConfigured,
  passwordConfigured,
}: Oauth2ConnectorFieldsProps) {
  const { t } = useTranslation();
  const secretExtra = (configured?: boolean) =>
    configured ? <Tag color="green">{t('apiGov.connectors.oauth2Configured')}</Tag> : t('apiGov.connectors.oauth2SecretHint');
  return (
    <>
      <Form.Item
        name="oauth2_token_uri"
        label={t('apiGov.connectors.oauth2TokenUri')}
        rules={[{ required: true, message: t('apiGov.connectors.oauth2TokenUriRequired'), max: 2048 }]}
      >
        <Input />
      </Form.Item>
      <Form.Item name="oauth2_grant_type" label={t('apiGov.connectors.oauth2GrantType')}>
        <Select options={enumOptions(OAUTH2_GRANT_TYPES, oauth2GrantTypeLabel, t)} />
      </Form.Item>
      <Form.Item name="oauth2_client_auth" label={t('apiGov.connectors.oauth2ClientAuth')}>
        <Select options={enumOptions(OAUTH2_CLIENT_AUTHS, oauth2ClientAuthLabel, t)} />
      </Form.Item>
      <Form.Item name="oauth2_client_id" label={t('apiGov.connectors.oauth2ClientId')} rules={[{ max: 512 }]}>
        <Input />
      </Form.Item>
      <Form.Item
        name="oauth2_client_secret"
        label={t('apiGov.connectors.oauth2ClientSecret')}
        extra={secretExtra(clientSecretConfigured)}
        rules={[{ max: 1024 }]}
      >
        <Input.Password autoComplete="new-password" />
      </Form.Item>
      <Form.Item name="oauth2_scopes" label={t('apiGov.connectors.oauth2Scopes')} rules={[{ max: 1024 }]}>
        <Input />
      </Form.Item>
      <Form.Item name="oauth2_audience" label={t('apiGov.connectors.oauth2Audience')} rules={[{ max: 512 }]}>
        <Input />
      </Form.Item>
      {grantType === 'REFRESH_TOKEN' && (
        <Form.Item
          name="oauth2_refresh_token"
          label={t('apiGov.connectors.oauth2RefreshToken')}
          extra={secretExtra(refreshTokenConfigured)}
          rules={[{ max: 4096 }]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      )}
      {grantType === 'PASSWORD' && (
        <>
          <Form.Item name="oauth2_username" label={t('apiGov.connectors.oauth2Username')} rules={[{ max: 255 }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="oauth2_password"
            label={t('apiGov.connectors.oauth2Password')}
            extra={secretExtra(passwordConfigured)}
            rules={[{ max: 1024 }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </>
      )}
    </>
  );
}
