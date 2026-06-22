import { App, Button, Tooltip } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { usePushSubscription } from '@/hooks/usePushSubscription';

/**
 * One-click opt-in to Web Push approvals (AF-444), surfaced on the review queue. Hidden entirely
 * on browsers without push support; disabled with an explanatory tooltip when the user has blocked
 * notifications.
 */
export function PushApprovalsToggle() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const { supported, permission, enabled, busy, enable, disable } = usePushSubscription();

  if (!supported) {
    return null;
  }
  const blocked = permission === 'denied';

  const onClick = async () => {
    try {
      if (enabled) {
        await disable();
        message.success(t('push.disabled'));
      } else {
        const ok = await enable();
        if (ok) {
          message.success(t('push.enabled'));
        } else {
          message.warning(t('push.permission_required'));
        }
      }
    } catch {
      message.error(t('push.error'));
    }
  };

  return (
    <Tooltip title={blocked ? t('push.blocked') : undefined}>
      <Button
        icon={<BellOutlined />}
        loading={busy}
        disabled={blocked}
        onClick={onClick}
        aria-label={enabled ? t('push.disable') : t('push.enable')}
      >
        {enabled ? t('push.disable') : t('push.enable')}
      </Button>
    </Tooltip>
  );
}
