import { Alert, Card, Skeleton, Space, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { getCurrentUser, meKeys } from '@/api/me';
import { DisplayNameForm } from './sections/DisplayNameForm';
import { ChangePasswordForm } from './sections/ChangePasswordForm';
import { TwoFactorSection } from './sections/TwoFactorSection';

export function ProfilePage() {
  const { t } = useTranslation();
  const { data: profile, isLoading, error } = useQuery({
    queryKey: meKeys.current,
    queryFn: getCurrentUser,
  });

  const isSaml = profile?.auth_provider === 'SAML';

  return (
    <div style={{ maxWidth: 720, margin: '0 auto', padding: '24px 0' }}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <div>
          <Typography.Title level={3} style={{ marginBottom: 4 }}>
            {t('profile.title')}
          </Typography.Title>
          <Typography.Text type="secondary">{t('profile.subtitle')}</Typography.Text>
        </div>

        {error && <Alert type="error" message={(error as Error).message} showIcon />}

        <Card title={t('profile.display_name.title')}>
          {isLoading || !profile ? <Skeleton active /> : <DisplayNameForm profile={profile} />}
        </Card>

        <Card title={t('profile.password.title')}>
          {isLoading || !profile ? (
            <Skeleton active />
          ) : isSaml ? (
            <Alert type="info" message={t('profile.password.saml_disabled')} showIcon />
          ) : (
            <ChangePasswordForm />
          )}
        </Card>

        <Card title={t('profile.totp.title')}>
          {isLoading || !profile ? (
            <Skeleton active />
          ) : isSaml ? (
            <Alert type="info" message={t('profile.totp.saml_disabled')} showIcon />
          ) : (
            <TwoFactorSection profile={profile} />
          )}
        </Card>
      </Space>
    </div>
  );
}
