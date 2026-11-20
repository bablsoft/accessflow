import { Tag, Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import { Pill } from '@/components/common/Pill';
import { deploymentOutcomeLabel } from '@/utils/enumLabels';
import { deploymentOutcomeColor, driftColor } from '@/utils/statusColors';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import type { DeploymentEnvironmentVersion, DeploymentVersionEnvironmentRef } from '@/types/api';
import { driftBadge, driftBadgeText, driftTooltipText, rollbackBadge, rollbackBadgeText } from './versionMatrix';

/** Shared cells for the org-wide matrix, the per-pipeline matrix and the deployment tables. */

export function Dash() {
  return <span className="muted">—</span>;
}

export function VersionCell({ value }: { value: string | null }) {
  if (value == null) return <Dash />;
  return (
    <span className="mono u-text-xs">
      {value}
    </span>
  );
}

export function EnvironmentCell({ environment }: { environment: DeploymentVersionEnvironmentRef }) {
  const { t } = useTranslation();
  return (
    <div className="u-flex u-col u-gap-1">
      <span>{environment.name}</span>
      {environment.tags.length > 0 && (
        <span
          aria-label={t('deploygov.versions.tags')}
          className="u-flex u-gap-1 u-wrap"
        >
          {environment.tags.map((tag) => (
            <Tag key={tag} style={{ marginInlineEnd: 0 }}>
              {tag}
            </Tag>
          ))}
        </span>
      )}
    </div>
  );
}

export function DeployedAtCell({ value }: { value: string | null }) {
  if (value == null) return <Dash />;
  return (
    <Tooltip title={fmtDate(value)}>
      <span className="muted u-text-xs">
        {timeAgo(value)}
      </span>
    </Tooltip>
  );
}

export function OutcomeCell({ row }: { row: DeploymentEnvironmentVersion }) {
  const { t } = useTranslation();
  const rollback = rollbackBadge(row);
  if (row.last_outcome == null) return <Dash />;
  const color = deploymentOutcomeColor(row.last_outcome);
  return (
    <div className="u-flex u-col u-gap-1 u-items-start">
      <Tag style={{ color: color.fg, background: color.bg, borderColor: color.border }}>
        {deploymentOutcomeLabel(t, row.last_outcome)}
      </Tag>
      {rollback.kind !== 'none' && (
        <span className="muted u-text-2xs">
          {rollbackBadgeText(t, rollback)}
        </span>
      )}
    </div>
  );
}

export function DriftChip({ row, note }: { row: DeploymentEnvironmentVersion; note?: string }) {
  const { t } = useTranslation();
  const badge = driftBadge(row);
  const color = driftColor(badge.drifted);
  const tooltip = note ? `${driftTooltipText(t, badge)} · ${note}` : driftTooltipText(t, badge);
  return (
    <Tooltip title={tooltip}>
      <span>
        <Pill fg={color.fg} bg={color.bg} border={color.border} withDot size="sm">
          {driftBadgeText(t, badge)}
        </Pill>
      </span>
    </Tooltip>
  );
}
