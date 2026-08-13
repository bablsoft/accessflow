import { describe, expect, it } from 'vitest';
import {
  dailyTotals,
  halfWindowDelta,
  pivotDailySeries,
  weeklyHeatmapColumns,
} from '../trendSeries';

const FROM = '2026-08-03T00:00:00.000Z'; // a Monday
const TO = '2026-08-10T00:00:00.000Z'; // exclusive

describe('pivotDailySeries', () => {
  it('produces one dense row per day with zero-fill for every label', () => {
    const rows = pivotDailySeries(
      [
        { date: '2026-08-03', label: 'Executed', count: 2 },
        { date: '2026-08-05', label: 'Rejected', count: 1 },
      ],
      FROM,
      TO,
    );
    expect(rows).toHaveLength(7);
    expect(rows[0]).toMatchObject({ Executed: 2, Rejected: 0 });
    expect(rows[2]).toMatchObject({ Executed: 0, Rejected: 1 });
    expect(rows[6]).toMatchObject({ Executed: 0, Rejected: 0 });
    expect(rows[0]?.date).toBeInstanceOf(Date);
  });

  it('sums multiple points on the same day and label', () => {
    const rows = pivotDailySeries(
      [
        { date: '2026-08-04', label: 'Executed', count: 1 },
        { date: '2026-08-04T10:00:00Z', label: 'Executed', count: 2 },
      ],
      FROM,
      TO,
    );
    expect(rows[1]).toMatchObject({ Executed: 3 });
  });

  it('returns an empty array for an empty window', () => {
    expect(pivotDailySeries([], FROM, FROM)).toEqual([]);
  });
});

describe('dailyTotals', () => {
  it('sums across series per day and zero-fills the window', () => {
    const totals = dailyTotals(
      [
        { date: '2026-08-03', count: 2 },
        { date: '2026-08-03', count: 3 },
        { date: '2026-08-09', count: 1 },
      ],
      FROM,
      TO,
    );
    expect(totals).toHaveLength(7);
    expect(totals[0]?.value).toBe(5);
    expect(totals[3]?.value).toBe(0);
    expect(totals[6]?.value).toBe(1);
  });
});

describe('halfWindowDelta', () => {
  it('compares the second half against the first', () => {
    const totals = [1, 1, 1, 2, 2, 2].map((value) => ({ value }));
    expect(halfWindowDelta(totals)).toEqual({ current: 6, previous: 3, delta: 3 });
  });

  it('puts the middle day in the current half for odd windows', () => {
    const totals = [1, 0, 4].map((value) => ({ value }));
    expect(halfWindowDelta(totals)).toEqual({ current: 4, previous: 1, delta: 3 });
  });

  it('handles an empty window', () => {
    expect(halfWindowDelta([])).toEqual({ current: 0, previous: 0, delta: 0 });
  });
});

describe('weeklyHeatmapColumns', () => {
  it('splits Monday-anchored weeks into columns with weekday rows', () => {
    const totals = dailyTotals([{ date: '2026-08-05', count: 4 }], FROM, '2026-08-17T00:00:00.000Z');
    const columns = weeklyHeatmapColumns(totals);
    expect(columns).toHaveLength(2);
    expect(columns[0]?.bins).toHaveLength(7);
    // 2026-08-05 is a Wednesday → row 2 (Monday = 0).
    expect(columns[0]?.bins[2]).toMatchObject({ bin: 2, count: 4 });
    expect(columns[1]?.bins).toHaveLength(7);
  });

  it('keeps a trailing partial week', () => {
    const totals = dailyTotals([], FROM, '2026-08-12T00:00:00.000Z');
    const columns = weeklyHeatmapColumns(totals);
    expect(columns).toHaveLength(2);
    expect(columns[1]?.bins).toHaveLength(2);
  });
});
