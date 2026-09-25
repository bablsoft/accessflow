import type { TFunction } from 'i18next';
import type { DataBudgetStatus } from '@/types/api';

/** Backend Bean Validation bounds on a budget (#942) — keep in step with `DataBudgetRequest`. */
export const DATA_BUDGET_NAME_MAX = 120;
export const DATA_BUDGET_WINDOW_MIN_MINUTES = 60;
export const DATA_BUDGET_WINDOW_MAX_MINUTES = 44_640;
export const DATA_BUDGET_THRESHOLD_MIN = 1;
export const DATA_BUDGET_THRESHOLD_MAX = 99;
export const DATA_BUDGET_DEFAULT_WINDOW_MINUTES = 1_440;

export type DataBudgetWindowUnit = 'minutes' | 'hours' | 'days';

export const DATA_BUDGET_WINDOW_UNITS: readonly DataBudgetWindowUnit[] = ['minutes', 'hours', 'days'];

const MINUTES_PER: Record<DataBudgetWindowUnit, number> = { minutes: 1, hours: 60, days: 1_440 };

/**
 * A stored window in minutes, split into the largest unit it fills exactly — never rounded, so
 * a window set through the API (say 90 minutes) survives an edit that only renames the budget.
 */
export function splitWindow(minutes: number): { amount: number; unit: DataBudgetWindowUnit } {
  if (minutes % MINUTES_PER.days === 0) {
    return { amount: minutes / MINUTES_PER.days, unit: 'days' };
  }
  if (minutes % MINUTES_PER.hours === 0) {
    return { amount: minutes / MINUTES_PER.hours, unit: 'hours' };
  }
  return { amount: minutes, unit: 'minutes' };
}

export function joinWindow(amount: number, unit: DataBudgetWindowUnit): number {
  return Math.round(amount * MINUTES_PER[unit]);
}

/** "90 minutes" / "24 hours" / "7 days" — in the largest unit the window fills exactly. */
export function formatWindow(t: TFunction, minutes: number): string {
  const { amount, unit } = splitWindow(minutes);
  if (unit === 'days') return t('dataBudgets.window_days', { count: amount });
  if (unit === 'hours') return t('dataBudgets.window_hours', { count: amount });
  return t('dataBudgets.window_minutes', { count: amount });
}

/**
 * True once an applying budget passes its warning threshold and before exhaustion. A budget with
 * no threshold never warns — the same rule the backend's threshold notification follows.
 */
export function budgetNearlyUsed(status: DataBudgetStatus): boolean {
  if (status.exhausted) return false;
  return status.budgets.some(
    (b) =>
      !b.exhausted && b.warn_threshold_percent != null && b.used_percent >= b.warn_threshold_percent,
  );
}
