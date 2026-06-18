import { Avatar, Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import type { CollabMember } from '@/types/ws';

interface PresenceBarProps {
  members: CollabMember[];
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
  return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase();
}

/**
 * Live roster of co-authors present in a query's collaboration room. Driven by server presence
 * frames; colour matches each user's remote cursor in the editor.
 */
export function PresenceBar({ members }: PresenceBarProps) {
  const { t } = useTranslation();
  if (members.length === 0) return null;
  return (
    <div
      style={{ display: 'flex', alignItems: 'center', gap: 8 }}
      aria-label={t('collab.presence_label', { count: members.length })}
    >
      <span className="muted" style={{ fontSize: 12 }}>
        {t('collab.present', { count: members.length })}
      </span>
      <Avatar.Group max={{ count: 5 }} size="small">
        {members.map((member) => (
          <Tooltip key={member.user_id} title={member.display_name}>
            <Avatar
              size="small"
              style={{ backgroundColor: member.color }}
              aria-label={member.display_name}
            >
              {initials(member.display_name)}
            </Avatar>
          </Tooltip>
        ))}
      </Avatar.Group>
    </div>
  );
}
