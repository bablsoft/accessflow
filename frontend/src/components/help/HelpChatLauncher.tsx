import { lazy, Suspense } from 'react';
import { Button, Tooltip } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { fetchHelpChatAvailability, helpChatKeys } from '@/api/helpChat';
import { usePreferencesStore } from '@/store/preferencesStore';
import './help-chat.css';

const HelpChatPanel = lazy(() =>
  import('./HelpChatPanel').then((m) => ({ default: m.HelpChatPanel })),
);

/**
 * The fixed help launcher, mounted inside `AppLayout` so it is authenticated-only and absent from
 * `/login` and `/setup`.
 *
 * It renders **nothing** — no button, and no drawer — unless the organization has the agent
 * switched on and bound. The availability query is the same cached entry `useHelpChat` reads, so
 * mounting the launcher costs one request per five minutes for the whole shell, and the panel's
 * own code is only fetched once a user opens it.
 */
export function HelpChatLauncher() {
  const { t } = useTranslation();
  const open = usePreferencesStore((s) => s.helpChatOpen);
  const setOpen = usePreferencesStore((s) => s.setHelpChatOpen);

  const { data } = useQuery({
    queryKey: helpChatKeys.availability(),
    queryFn: fetchHelpChatAvailability,
    staleTime: 5 * 60_000,
  });

  if (!data?.enabled) return null;

  return (
    <>
      <Tooltip title={t('help_chat.launcher_tooltip')} placement="left">
        <Button
          className="af-help-launcher"
          type="primary"
          shape="circle"
          size="large"
          icon={<QuestionCircleOutlined />}
          onClick={() => setOpen(!open)}
          aria-label={t('help_chat.launcher_tooltip')}
        />
      </Tooltip>
      {open ? (
        <Suspense fallback={null}>
          <HelpChatPanel open={open} onClose={() => setOpen(false)} />
        </Suspense>
      ) : null}
    </>
  );
}

export default HelpChatLauncher;
