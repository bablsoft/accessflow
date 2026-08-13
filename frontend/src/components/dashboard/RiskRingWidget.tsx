import { useMemo } from 'react';
import { SafetyOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { WidgetError } from '@/components/dashboard/WidgetError';
import { RingCenter, RingChart, type RingData } from '@/components/charts';
import { trendsFiltersForRange } from '@/utils/trendSeries';
import { dashboardKeys, fetchMyQueryTrends } from '@/api/dashboard';
import { usePreferencesStore } from '@/store/preferencesStore';
import { riskLevelLabel } from '@/utils/enumLabels';
import { riskColor } from '@/utils/riskColors';
import type { RiskLevel } from '@/types/api';

const RING_ORDER: RiskLevel[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

/**
 * Risk mix of the user's own queries over the trends window (AF-498 redesign): one Bklit ring
 * per risk level, colored by the risk tokens, sharing the trends range preference and cache.
 */
export function RiskRingWidget() {
  const { t } = useTranslation();
  const range = usePreferencesStore((s) => s.dashboardTrendsRange);
  const filters = useMemo(() => trendsFiltersForRange(range, new Date()), [range]);

  const trendsQuery = useQuery({
    queryKey: dashboardKeys.trends(filters),
    queryFn: () => fetchMyQueryTrends(filters),
  });

  const { rings, total } = useMemo(() => {
    const sums = new Map<RiskLevel, number>();
    for (const b of trendsQuery.data?.risk_by_day ?? []) {
      sums.set(b.risk_level, (sums.get(b.risk_level) ?? 0) + b.count);
    }
    const sum = [...sums.values()].reduce((s, v) => s + v, 0);
    const data: RingData[] = RING_ORDER.filter((level) => (sums.get(level) ?? 0) > 0).map(
      (level) => ({
        label: riskLevelLabel(t, level),
        value: sums.get(level) ?? 0,
        maxValue: sum,
        color: riskColor(level).fg,
      }),
    );
    return { rings: data, total: sum };
  }, [trendsQuery.data, t]);

  if (trendsQuery.isError) {
    return <WidgetError error={trendsQuery.error} onRetry={() => void trendsQuery.refetch()} />;
  }
  if (!trendsQuery.isLoading && rings.length === 0) {
    return (
      <EmptyState
        size="sm"
        icon={<SafetyOutlined style={{ fontSize: 16 }} />}
        title={t('dashboard.risk_mix.empty')}
      />
    );
  }
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 20, flexWrap: 'wrap' }}>
      <div style={{ width: 190, height: 190, flex: 'none' }}>
        <RingChart data={rings} strokeWidth={10} ringGap={5} baseInnerRadius={46}>
          <RingCenter>
            {() => (
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 22, fontWeight: 600 }}>{total}</div>
                <div className="muted" style={{ fontSize: 11 }}>
                  {t('dashboard.risk_mix.total')}
                </div>
              </div>
            )}
          </RingCenter>
        </RingChart>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 120 }}>
        {rings.map((ring) => (
          <span
            key={ring.label}
            style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontSize: 13 }}
          >
            <span
              aria-hidden
              style={{
                width: 9,
                height: 9,
                borderRadius: '50%',
                background: ring.color,
                display: 'inline-block',
              }}
            />
            <span style={{ flex: 1 }}>{ring.label}</span>
            <span className="mono muted" style={{ fontSize: 12 }}>
              {ring.value}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}
