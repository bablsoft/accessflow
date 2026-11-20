import type { ReactNode } from 'react';
import { Card } from 'antd';

interface MetricTileProps {
  label: string;
  value: ReactNode;
  testId: string;
  /** Secondary lines under the value — p95, sample counts, caveats. */
  caption?: ReactNode;
}

/**
 * A read-only headline number on the deployment-metrics page (#987). Not `StatTile`: that one
 * is a numeric, always-clickable dashboard tile, while these values are formatted strings with
 * nowhere to navigate.
 */
export function MetricTile({ label, value, testId, caption }: MetricTileProps) {
  return (
    <Card size="small" data-testid={testId} className="u-h-full">
      <div className="muted u-text-xs">
        {label}
      </div>
      <div
        className="mono u-fw-600 u-mt-1"
        data-testid={`${testId}-value`}
        style={{ fontSize: 22 }}
      >
        {value}
      </div>
      {caption && (
        <div
          className="muted u-text-2xs u-mt-1 u-flex u-col u-gap-1"
        >
          {caption}
        </div>
      )}
    </Card>
  );
}
