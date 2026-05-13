import { Alert, Card, Skeleton, Space } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { getCurrentUser, meKeys } from '@/api/me';
import { PageHeader } from '@/components/common/PageHeader';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';
import { apiErrorTraceId } from '@/utils/apiErrors';
import { DisplayNameForm } from './sections/DisplayNameForm';
import { ChangePasswordForm } from './sections/ChangePasswordForm';
import { TwoFactorSection } from './sections/TwoFactorSection';

export function ProfilePage() {
  const { t } = useTranslation();
  const { data: profile, isLoading, error } = useQuery({
    queryKey: meKeys.current,
    queryFn: getCurrentUser,
  });

  const isExternal =
    profile?.auth_provider === 'SAML' || profile?.auth_provider === 'OAUTH2';
  const externalPasswordMessage =
    profile?.auth_provider === 'OAUTH2'
      ? t('profile.password.oauth2_disabled')
      : t('profile.password.saml_disabled');
  const externalTotpMessage =
    profile?.auth_provider === 'OAUTH2'
      ? t('profile.totp.oauth2_disabled')
      : t('profile.totp.saml_disabled');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('profile.title')} subtitle={t('profile.subtitle')} />
      <div style={{ flex: 1, overflow: 'auto', padding: 28 }}>
        <div style={{ maxWidth: 720, margin: '0 auto' }}>
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            {error && (() => {
              const traceId = apiErrorTraceId(error);
              return (
                <Alert
                  type="error"
                  message={(error as Error).message}
                  description={traceId ? <TraceIdFooter traceId={traceId} /> : undefined}
                  showIcon
                />
              );
            })()}

            <Card title={t('profile.display_name.title')}>
              {isLoading || !profile ? <Skeleton active /> : <DisplayNameForm profile={profile} />}
            </Card>

            <Card title={t('profile.password.title')}>
              {isLoading || !profile ? (
                <Skeleton active />
              ) : isExternal ? (
                <Alert type="info" message={externalPasswordMessage} showIcon />
              ) : (
                <ChangePasswordForm />
              )}
            </Card>

            <Card title={t('profile.totp.title')}>
              {isLoading || !profile ? (
                <Skeleton active />
              ) : isExternal ? (
                <Alert type="info" message={externalTotpMessage} showIcon />
              ) : (
                <TwoFactorSection profile={profile} />
              )}
            </Card>
          </Space>
        </div>
      </div>
    </div>
  );
}
