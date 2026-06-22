import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Tooltip } from 'antd';
import { WarningFilled } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { anomalyKeys, fetchAnomalyBadge } from '@/api/anomalies';

interface AnomalyBadgeProps {
  /** Pre-resolved counts. When omitted, the badge fetches its own via `fetchAnomalyBadge`. */
  openCount?: number;
  maxScore?: number;
  /** Scopes a self-fetch to a single datasource; ignored when `openCount` is provided. */
  datasourceId?: string;
  /** When true, the chip links to the anomalies dashboard. */
  link?: boolean;
}

function Chip({ openCount, maxScore }: { openCount: number; maxScore: number }) {
  const { t } = useTranslation();
  return (
    <Tooltip title={t('anomalies.badge.tooltip', { count: openCount, score: maxScore.toFixed(1) })}>
      <span
        className="af-pill af-pill-sm"
        style={{
          color: 'var(--risk-crit)',
          background: 'var(--risk-crit-bg)',
          borderColor: 'var(--risk-crit-border)',
        }}
        data-testid="anomaly-badge"
      >
        <WarningFilled style={{ fontSize: 11 }} />
        {t('anomalies.badge.label', { count: openCount })}
      </span>
    </Tooltip>
  );
}

export function AnomalyBadge({ openCount, maxScore, datasourceId, link = false }: AnomalyBadgeProps) {
  const controlled = typeof openCount === 'number';
  const badgeQuery = useQuery({
    queryKey: anomalyKeys.badge(datasourceId),
    queryFn: () => fetchAnomalyBadge(datasourceId),
    enabled: !controlled,
  });

  const count = controlled ? openCount : badgeQuery.data?.openCount ?? 0;
  const score = controlled ? maxScore ?? 0 : badgeQuery.data?.maxScore ?? 0;

  if (count <= 0) return null;

  const chip = <Chip openCount={count} maxScore={score} />;
  if (link) {
    return (
      <Link to="/admin/anomalies" style={{ textDecoration: 'none' }}>
        {chip}
      </Link>
    );
  }
  return chip;
}
