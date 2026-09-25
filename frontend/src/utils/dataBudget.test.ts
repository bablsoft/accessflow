import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import { budgetNearlyUsed, formatWindow, joinWindow, splitWindow } from './dataBudget';
import type { DataBudgetConsumption, DataBudgetStatus } from '@/types/api';

const t = ((key: string, opts?: { count?: number }) => `${key}:${opts?.count}`) as unknown as TFunction;

const consumption = (overrides: Partial<DataBudgetConsumption>): DataBudgetConsumption => ({
  id: 'b',
  name: 'b',
  window_minutes: 60,
  breach_action: 'REJECT',
  used_rows: 0,
  used_bytes: 0,
  used_percent: 0,
  exhausted: false,
  ...overrides,
});

const status = (budgets: DataBudgetConsumption[], exhausted = false): DataBudgetStatus => ({
  datasource_id: 'ds',
  exhausted,
  budgets,
});

describe('utils/dataBudget', () => {
  it('splits and joins windows in whole units', () => {
    expect(splitWindow(1440)).toEqual({ amount: 1, unit: 'days' });
    expect(splitWindow(10_080)).toEqual({ amount: 7, unit: 'days' });
    expect(splitWindow(120)).toEqual({ amount: 2, unit: 'hours' });
    expect(splitWindow(90)).toEqual({ amount: 90, unit: 'minutes' });
    expect(joinWindow(90, 'minutes')).toBe(90);
    expect(joinWindow(3, 'days')).toBe(4320);
    expect(joinWindow(6, 'hours')).toBe(360);
  });

  it('formats a window for display', () => {
    expect(formatWindow(t, 2880)).toBe('dataBudgets.window_days:2');
    expect(formatWindow(t, 60)).toBe('dataBudgets.window_hours:1');
    expect(formatWindow(t, 90)).toBe('dataBudgets.window_minutes:90');
  });

  it('flags a budget past its warning threshold but not an exhausted one', () => {
    expect(budgetNearlyUsed(status([consumption({ used_percent: 85, warn_threshold_percent: 80 })])))
      .toBe(true);
    expect(budgetNearlyUsed(status([consumption({ used_percent: 95 })]))).toBe(false);
    expect(budgetNearlyUsed(status([consumption({ used_percent: 50, warn_threshold_percent: 40 })])))
      .toBe(true);
    expect(budgetNearlyUsed(status([consumption({ used_percent: 50 })]))).toBe(false);
    expect(budgetNearlyUsed(status([consumption({ used_percent: 100, exhausted: true })], true)))
      .toBe(false);
  });
});
