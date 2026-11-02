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
    <Card size="small" data-testid={testId} style={{ height: '100%' }}>
      <div className="muted" style={{ fontSize: 12 }}>
        {label}
      </div>
      <div
        className="mono"
        data-testid={`${testId}-value`}
        style={{ fontSize: 22, fontWeight: 600, marginTop: 4 }}
      >
        {value}
      </div>
      {caption && (
        <div
          className="muted"
          style={{ fontSize: 11, marginTop: 4, display: 'flex', flexDirection: 'column', gap: 2 }}
        >
          {caption}
        </div>
      )}
    </Card>
  );
}
