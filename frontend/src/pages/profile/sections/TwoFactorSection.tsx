import { useState } from 'react';
import { Button, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { MeProfile } from '@/types/api';
import { TotpEnrollmentDialog } from './TotpEnrollmentDialog';
import { TotpDisableDialog } from './TotpDisableDialog';

interface TwoFactorSectionProps {
  profile: MeProfile;
}

export function TwoFactorSection({ profile }: TwoFactorSectionProps) {
  const { t } = useTranslation();
  const [enrollOpen, setEnrollOpen] = useState(false);
  const [disableOpen, setDisableOpen] = useState(false);

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {profile.totp_enabled ? (
        <>
          <Space>
            <Tag color="green">{t('profile.totp.enabled_badge')}</Tag>
          </Space>
          <Typography.Paragraph>{t('profile.totp.subtitle_enabled')}</Typography.Paragraph>
          <Button danger onClick={() => setDisableOpen(true)}>
            {t('profile.totp.disable')}
          </Button>
        </>
      ) : (
        <>
          <Typography.Paragraph>{t('profile.totp.subtitle_disabled')}</Typography.Paragraph>
          <Button type="primary" onClick={() => setEnrollOpen(true)}>
            {t('profile.totp.enable')}
          </Button>
        </>
      )}

      <TotpEnrollmentDialog open={enrollOpen} onClose={() => setEnrollOpen(false)} />
      <TotpDisableDialog open={disableOpen} onClose={() => setDisableOpen(false)} />
    </Space>
  );
}
