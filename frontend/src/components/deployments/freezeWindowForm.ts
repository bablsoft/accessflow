import type { TFunction } from 'i18next';
import type {
  DeploymentFreezeWindow,
  DeploymentFreezeWindowInput,
  FreezeBehavior,
} from '@/types/api';
import { fmtDate } from '@/utils/dateFormat';
import { isoWeekdayLabel } from '@/utils/enumLabels';

export type FreezeWindowMode = 'one_off' | 'recurring';

/**
 * Flat form model for the freeze-window modal. One-off fields and recurring fields are mutually
 * exclusive on the wire (the backend rejects mixed shapes with DEPLOYMENT_FREEZE_WINDOW_INVALID),
 * so `toWireInput` nulls whichever half the selected mode does not use.
 */
export interface FreezeWindowFormValues {
  mode: FreezeWindowMode;
  pipeline_id: string | null;
  environment_id: string | null;
  starts_at: string | null;
  ends_at: string | null;
  days_of_week: number[];
  start_time: string | null;
  end_time: string | null;
  timezone: string | null;
  behavior: FreezeBehavior;
  reason: string | null;
  enabled: boolean;
}

export function windowMode(window: DeploymentFreezeWindow): FreezeWindowMode {
  return (window.days_of_week ?? []).length > 0 ? 'recurring' : 'one_off';
}

/** Normalize the wire "HH:mm[:ss]" LocalTime rendering to "HH:mm" for form fields and display. */
export function normalizeTime(time: string | null): string | null {
  if (!time) return null;
  const parts = time.split(':');
  if (parts.length < 2) return time;
  return `${parts[0]}:${parts[1]}`;
}

export function toWireInput(values: FreezeWindowFormValues): DeploymentFreezeWindowInput {
  const oneOff = values.mode === 'one_off';
  return {
    pipeline_id: values.pipeline_id,
    environment_id: values.pipeline_id ? values.environment_id : null,
    starts_at: oneOff ? values.starts_at : null,
    ends_at: oneOff ? values.ends_at : null,
    days_of_week: oneOff ? null : values.days_of_week,
    start_time: oneOff ? null : values.start_time,
    end_time: oneOff ? null : values.end_time,
    timezone: oneOff ? null : values.timezone,
    behavior: values.behavior,
    reason: values.reason,
    enabled: values.enabled,
  };
}

/** Compact one-line schedule summary for the freeze-window table. */
export function freezeWindowSummary(t: TFunction, window: DeploymentFreezeWindow): string {
  if (windowMode(window) === 'one_off') {
    return t('deploygov.freezeWindows.summary_one_off', {
      from: window.starts_at ? fmtDate(window.starts_at) : '—',
      to: window.ends_at ? fmtDate(window.ends_at) : '—',
    });
  }
  const days = [...(window.days_of_week ?? [])]
    .sort((a, b) => a - b)
    .map((d) => isoWeekdayLabel(t, d))
    .join(', ');
  return t('deploygov.freezeWindows.summary_recurring', {
    days,
    start: normalizeTime(window.start_time) ?? '—',
    end: normalizeTime(window.end_time) ?? '—',
    timezone: window.timezone ?? 'UTC',
  });
}

/** The IANA zone list for the timezone select, with the browser's own zone first. */
export function timezoneOptions(): string[] {
  const zones =
    typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : ['UTC'];
  const local = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const rest = zones.filter((z) => z !== local);
  return local ? [local, ...rest] : [...zones];
}
