import { RocketOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { ActivityList } from '@/components/dashboard/ActivityList';
import { StatusPill } from '@/components/common/StatusPill';
import { deploymentOutcomeLabel } from '@/utils/enumLabels';
import { deploymentOutcomeColor } from '@/utils/statusColors';
import { timeAgo } from '@/utils/dateFormat';
import type { DashboardRecentDeployment } from '@/types/api';

interface Props {
  items: DashboardRecentDeployment[];
  loading: boolean;
  error?: unknown;
  onRetry?: () => void;
}

/** The current user's most recent governed deployment requests, with status + outcome (#926). */
export function MyDeploymentsWidget({ items, loading, error, onRetry }: Props) {
  const { t } = useTranslation();
  return (
    <ActivityList
      items={items}
      loading={loading}
      error={error}
      onRetry={onRetry}
      emptyIcon={<RocketOutlined style={{ fontSize: 16 }} />}
      emptyTitle={t('dashboard.my_deployments.empty')}
      rowKey={(it) => it.id}
      viewAllTo="/deployments"
      renderRow={(it) => {
        // The outcome only exists once CI reported back; until then the status pill is the story.
        // Normalized with `??` rather than compared to null: the backend runs Jackson with
        // `default-property-inclusion: non_null`, so a null field is *omitted* from the payload
        // and arrives as `undefined`. A strict `=== null` guard let it through to
        // `deploymentOutcomeColor`, whose switch is exhaustive over the three real values and has
        // no default — it returned `undefined`, and reading `.fg` off that unmounted the whole app.
        const outcome = it.outcome ?? null;
        const color = outcome === null ? null : deploymentOutcomeColor(outcome);
        return {
          pills: (
            <>
              <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>
                {it.version}
              </span>
              <StatusPill status={it.status} size="sm" />
              {outcome !== null && color !== null && (
                <Tag
                  style={{ color: color.fg, background: color.bg, borderColor: color.border }}
                >
                  {deploymentOutcomeLabel(t, outcome)}
                </Tag>
              )}
            </>
          ),
          primary: (
            <>
              <span style={{ fontWeight: 500 }}>{it.pipeline_name ?? '—'}</span>{' '}
              <span className="muted">{it.environment_name ?? '—'}</span>
            </>
          ),
          meta: timeAgo(it.created_at),
          action: <Link to={`/deployments/${it.id}`}>{t('dashboard.my_deployments.view')}</Link>,
        };
      }}
    />
  );
}
