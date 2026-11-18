import { useMemo } from 'react';
import { Command } from 'cmdk';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { BulbOutlined, EditOutlined, HistoryOutlined, UserOutlined } from '@ant-design/icons';
import type { AuthUser } from '@/api/auth';
import { listQueries, queryKeys } from '@/api/queries';
import { buildNavGroups } from '@/components/common/Sidebar';
import { flattenNav, visibleNavGroups, type NavEntry } from '@/components/common/navModel';
import { StatusPill } from '@/components/common/StatusPill';
import { usePreferencesStore } from '@/store/preferencesStore';
import { hasAnyPermission } from '@/utils/permissions';
import './command-palette.css';

const RECENT_QUERIES = { page: 0, size: 5 } as const;

interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
  user: AuthUser;
}

const trail = (entry: NavEntry) => [entry.groupLabel, entry.subLabel].filter(Boolean).join(' › ');

/** Search-and-jump over every destination the user can see, plus a few global actions. */
export function CommandPalette({ open, onClose, user }: CommandPaletteProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const recentIds = usePreferencesStore((s) => s.navRecent);
  const theme = usePreferencesStore((s) => s.theme);
  const setTheme = usePreferencesStore((s) => s.setTheme);

  const entries = useMemo(() => flattenNav(visibleNavGroups(buildNavGroups(t), user)), [t, user]);
  const recent = recentIds
    .map((id) => entries.find((e) => e.item.id === id))
    .filter((e): e is NavEntry => e !== undefined);

  const canListQueries = hasAnyPermission(user, ['QUERY_SUBMIT_SELECT']);
  const { data: queries } = useQuery({
    queryKey: queryKeys.list(RECENT_QUERIES),
    queryFn: () => listQueries(RECENT_QUERIES),
    enabled: open && canListQueries,
  });

  const run = (action: () => void) => {
    onClose();
    action();
  };

  const page = (entry: NavEntry, prefix: string) => (
    <Command.Item
      key={`${prefix}-${entry.item.id}`}
      value={`${prefix} ${entry.item.label} ${trail(entry)}`}
      onSelect={() => run(() => navigate(entry.item.to))}
    >
      <span className="af-cmd-icon" aria-hidden>{entry.item.icon}</span>
      <span className="af-cmd-label">{entry.item.label}</span>
      <span className="af-cmd-hint">{trail(entry)}</span>
    </Command.Item>
  );

  return (
    <Command.Dialog
      open={open}
      onOpenChange={(next) => { if (!next) onClose(); }}
      label={t('command.label')}
      overlayClassName="af-cmd-overlay"
      contentClassName="af-cmd-dialog"
    >
      <Command.Input placeholder={t('command.placeholder')} />
      <Command.List>
        <Command.Empty>{t('command.empty')}</Command.Empty>
        {recent.length > 0 && (
          <Command.Group heading={t('command.group_recent')}>
            {recent.map((e) => page(e, 'recent'))}
          </Command.Group>
        )}
        <Command.Group heading={t('command.group_actions')}>
          {entries.some((e) => e.item.id === 'editor') && (
            <Command.Item onSelect={() => run(() => navigate('/editor'))}>
              <span className="af-cmd-icon" aria-hidden><EditOutlined /></span>
              <span className="af-cmd-label">{t('command.action_new_query')}</span>
            </Command.Item>
          )}
          <Command.Item onSelect={() => run(() => setTheme(theme === 'dark' ? 'light' : 'dark'))}>
            <span className="af-cmd-icon" aria-hidden><BulbOutlined /></span>
            <span className="af-cmd-label">{t('command.action_toggle_theme')}</span>
          </Command.Item>
          <Command.Item onSelect={() => run(() => navigate('/profile'))}>
            <span className="af-cmd-icon" aria-hidden><UserOutlined /></span>
            <span className="af-cmd-label">{t('command.action_profile')}</span>
          </Command.Item>
        </Command.Group>
        <Command.Group heading={t('command.group_pages')}>
          {entries.map((e) => page(e, 'page'))}
        </Command.Group>
        {queries && queries.content.length > 0 && (
          <Command.Group heading={t('command.group_queries')}>
            {queries.content.map((q) => (
              <Command.Item
                key={q.id}
                value={`query ${q.id} ${q.datasource.name}`}
                onSelect={() => run(() => navigate(`/queries/${q.id}`))}
              >
                <span className="af-cmd-icon" aria-hidden><HistoryOutlined /></span>
                <span className="af-cmd-label mono">{q.id.slice(0, 8)}</span>
                <span className="af-cmd-hint">{q.datasource.name}</span>
                <StatusPill status={q.status} />
              </Command.Item>
            ))}
          </Command.Group>
        )}
      </Command.List>
    </Command.Dialog>
  );
}
