import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import { bklitChartMocks } from './chartsTestMocks';

vi.mock('@/components/charts', () => bklitChartMocks());

const { StatTile } = await import('./StatTile');

describe('StatTile', () => {
  it('renders label, value and children, and opens on click', () => {
    const onOpen = vi.fn();
    render(
      <StatTile label="Open queries" value={7} testId="dashboard-stat-open" onOpen={onOpen}>
        <span data-testid="breakdown" />
      </StatTile>,
    );
    expect(screen.getByText('Open queries')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
    expect(screen.getByTestId('breakdown')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('dashboard-stat-open'));
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('opens via Enter and Space keys', () => {
    const onOpen = vi.fn();
    render(<StatTile label="Pending" value={1} testId="t" onOpen={onOpen} />);
    const tile = screen.getByTestId('t');
    fireEvent.keyDown(tile, { key: 'Enter' });
    fireEvent.keyDown(tile, { key: ' ' });
    fireEvent.keyDown(tile, { key: 'Escape' });
    expect(onOpen).toHaveBeenCalledTimes(2);
  });

  it('is keyboard-focusable with a localized aria-label', () => {
    render(<StatTile label="Pending" value={1} testId="t" onOpen={() => {}} />);
    const tile = screen.getByTestId('t');
    expect(tile).toHaveAttribute('tabindex', '0');
    expect(tile).toHaveAttribute('aria-label', 'Open Pending');
  });

  it('renders the sparkline and delta chip when a trend is provided', () => {
    render(
      <StatTile
        label="Open queries"
        value={7}
        testId="t"
        onOpen={() => {}}
        trend={{
          spark: [
            { date: new Date('2026-08-01'), value: 1 },
            { date: new Date('2026-08-02'), value: 4 },
          ],
          delta: 3,
          previous: 1,
        }}
      />,
    );
    expect(screen.getByTestId('bklit-line-chart')).toHaveAttribute('data-rows', '2');
    expect(screen.getByTestId('bklit-line')).toHaveAttribute('data-key', 'value');
  });

  it('omits the sparkline for a single-point trend', () => {
    render(
      <StatTile
        label="Open queries"
        value={7}
        testId="t"
        onOpen={() => {}}
        trend={{ spark: [{ date: new Date('2026-08-01'), value: 1 }], delta: 1, previous: 0 }}
      />,
    );
    expect(screen.queryByTestId('bklit-line-chart')).not.toBeInTheDocument();
  });
});
