import { Alert, Button, Modal, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { IssuedServiceAccountKey, RotatedServiceAccountKey } from '@/types/api';
import { fmtDate } from '@/utils/dateFormat';

interface IssuedKeyModalProps {
  issued: IssuedServiceAccountKey | RotatedServiceAccountKey | null;
  onClose: () => void;
}

function isRotation(
  issued: IssuedServiceAccountKey | RotatedServiceAccountKey,
): issued is RotatedServiceAccountKey {
  return 'superseded_key' in issued;
}

/**
 * The show-once treatment for a freshly issued key (#875, from the profile's `ApiKeysSection`):
 * the plaintext appears here and nowhere else. A rotation also states until when the previous
 * key keeps working, so an operator knows the window they have to update the consumer.
 */
export function IssuedKeyModal({ issued, onClose }: IssuedKeyModalProps) {
  const { t } = useTranslation();
  const rotation = issued && isRotation(issued) ? issued : null;
  return (
    <Modal
      title={t(rotation ? 'admin.service_accounts.keys.rotated_title' : 'admin.service_accounts.keys.issued_title')}
      open={Boolean(issued)}
      onCancel={onClose}
      footer={[
        <Button key="close" type="primary" onClick={onClose}>
          {t('common.close')}
        </Button>,
      ]}
      destroyOnHidden
    >
      {issued && (
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <Alert type="warning" showIcon title={t('admin.service_accounts.keys.copy_once_warning')} />
          <Typography.Text strong>{t('admin.service_accounts.keys.name_label')}</Typography.Text>
          <Typography.Text>{issued.api_key.name}</Typography.Text>
          <Typography.Text strong>{t('admin.service_accounts.keys.raw_key_label')}</Typography.Text>
          <Typography.Paragraph
            copyable={{ text: issued.raw_key }}
            code
            style={{ wordBreak: 'break-all' }}
            data-testid="issued-raw-key"
          >
            {issued.raw_key}
          </Typography.Paragraph>
          {rotation && (
            <Alert
              type="info"
              showIcon
              data-testid="superseded-key-note"
              title={
                rotation.superseded_key.expires_at
                  ? t('admin.service_accounts.keys.superseded_until', {
                      prefix: rotation.superseded_key.key_prefix,
                      until: fmtDate(rotation.superseded_key.expires_at),
                    })
                  : t('admin.service_accounts.keys.superseded_until_unknown', {
                      prefix: rotation.superseded_key.key_prefix,
                    })
              }
            />
          )}
        </Space>
      )}
    </Modal>
  );
}
