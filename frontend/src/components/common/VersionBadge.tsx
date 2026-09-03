import { useQuery } from '@tanstack/react-query';
import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import { fetchUpdateStatus, updateKeys } from '@/api/updates';
import { CHANGELOG_URL } from '@/config/docs';
import { APP_VERSION } from '@/config/version';
import { driftColor } from '@/utils/statusColors';
import { Pill } from './Pill';

/**
 * The version rendered under the brand mark in the sidebar. When the backend reports a newer
 * stable release it becomes a warn-toned chip (the #743 drift triple — being behind is an
 * operational fact, not a failure) linking to that release's changelog entry. Shown to every
 * signed-in user (#836).
 */
export function VersionBadge() {
  const { t } = useTranslation();
  // Overrides the global 30 s staleTime / retry: this is an install-level signal the backend
  // already caches for a day, so one fetch per session is plenty and it must never poll; and a
  // failed check has to stay silent — the plain version is the right fallback, not a retry storm.
  const { data } = useQuery({
    queryKey: updateKeys.status(),
    queryFn: fetchUpdateStatus,
    staleTime: 60 * 60_000,
    retry: false,
  });

  // A newer release without a version string cannot be announced meaningfully — stay plain.
  if (!data?.update_available || !data.latest_version) {
    return (
      <div
        className="mono muted"
        style={{ fontSize: 9.5, textTransform: 'lowercase' }}
        aria-label={t('nav.version', { version: APP_VERSION })}
      >
        v{APP_VERSION}
      </div>
    );
  }

  const label = t('nav.update_available', { version: data.latest_version });
  const color = driftColor(true);
  return (
    <Tooltip title={label}>
      <a
        href={data.changelog_url ?? CHANGELOG_URL}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={label}
        style={{ display: 'inline-flex', marginTop: 2 }}
      >
        <Pill fg={color.fg} bg={color.bg} border={color.border} withDot size="sm">
          v{APP_VERSION}
        </Pill>
      </a>
    </Tooltip>
  );
}
