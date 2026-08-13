import type { ReactNode } from 'react';
import { Card, Statistic } from 'antd';
import { useTranslation } from 'react-i18next';
import { DeltaBadge } from '@/components/common/DeltaBadge';
import { Line, LineChart } from '@/components/charts';

export interface StatTileTrend {
  /** Dense per-day rows (`{ date: Date, value: number }`) rendered as a mini sparkline. */
  spark: Array<Record<string, unknown>>;
  /** Second-half vs first-half comparison of the window. */
  delta: number;
  previous: number;
}

interface StatTileProps {
  label: string;
  value: number;
  testId: string;
  /** Navigating (or scrolling) the tile leads somewhere useful; the whole surface is the target. */
  onOpen: () => void;
  /** Optional activity trend: sparkline + delta chip (AF-498 redesign, Bklit line chart). */
  trend?: StatTileTrend;
  /** Optional secondary row (e.g. the per-status breakdown under open queries). */
  children?: ReactNode;
}

/** A clickable, keyboard-accessible headline stat tile (AF-498 redesign). */
export function StatTile({ label, value, testId, onOpen, trend, children }: StatTileProps) {
  const { t } = useTranslation();
  return (
    <Card
      size="small"
      className="af-stat-tile"
      data-testid={testId}
      // Button (not link) semantics: the tile activates on both Enter and Space, and the
      // suggestions tile performs an in-page action rather than a navigation.
      role="button"
      tabIndex={0}
      aria-label={t('dashboard.stat_open', { label })}
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onOpen();
        }
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
        <Statistic title={label} value={value} />
        {trend && <DeltaBadge delta={trend.delta} previous={trend.previous} size="sm" positiveIsGood />}
      </div>
      {trend && trend.spark.length > 1 && (
        <div className="af-stat-spark" aria-hidden>
          <LineChart data={trend.spark} aspectRatio="7 / 1" animationDuration={700}>
            <Line dataKey="value" strokeWidth={1.5} />
          </LineChart>
        </div>
      )}
      {children}
    </Card>
  );
}
