import type { ReactNode } from 'react';

/**
 * Lightweight stand-ins for the vendored Bklit charts (test-only): jsdom cannot lay out
 * visx/motion SVG, so widget tests assert data-shape attributes on these stubs instead.
 * Used via `vi.mock('@/components/charts', () => bklitChartMocks())`.
 */
export function bklitChartMocks() {
  const passthrough = ({ children }: { children?: ReactNode }) => <>{children}</>;
  return {
    AreaChart: ({ data, children, status }: { data: unknown[]; children?: ReactNode; status?: string }) => (
      <div data-testid="bklit-area-chart" data-rows={data.length} data-status={status ?? 'ready'}>
        {children}
      </div>
    ),
    Area: ({ dataKey }: { dataKey: string }) => <div data-testid="bklit-area" data-key={dataKey} />,
    LineChart: ({ data, children }: { data: unknown[]; children?: ReactNode }) => (
      <div data-testid="bklit-line-chart" data-rows={data.length}>
        {children}
      </div>
    ),
    Line: ({ dataKey }: { dataKey: string }) => <div data-testid="bklit-line" data-key={dataKey} />,
    Grid: () => null,
    XAxis: () => null,
    ChartTooltip: () => null,
    RingChart: ({ data, children }: { data: Array<{ label: string; value: number }>; children?: ReactNode }) => (
      <div data-testid="bklit-ring-chart" data-rings={data.length}>
        {children}
      </div>
    ),
    RingCenter: ({ children }: { children?: unknown }) => (
      <div data-testid="bklit-ring-center">
        {typeof children === 'function'
          ? (children as (p: Record<string, unknown>) => ReactNode)({})
          : (children as ReactNode)}
      </div>
    ),
    HeatmapChart: ({ data, children, status }: { data: unknown[]; children?: ReactNode; status?: string }) => (
      <div data-testid="bklit-heatmap-chart" data-columns={data.length} data-status={status ?? 'ready'}>
        {children}
      </div>
    ),
    HeatmapCells: () => null,
    HeatmapXAxis: () => null,
    HeatmapYAxis: () => null,
    HeatmapTooltip: () => null,
    HeatmapLegend: () => null,
    HeatmapInteractionProvider: passthrough,
    HeatmapInteractionBoundary: passthrough,
  };
}
