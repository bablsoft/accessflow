import type { TFunction } from 'i18next';
import type {
  DeploymentVerificationConfig,
  DeploymentVerificationConfigInput,
  VerificationCheckInput,
  VerificationCheckKind,
  VerificationComparator,
} from '@/types/api';

/**
 * Client-side mirror of the backend's verification rules: Bean Validation on
 * DeploymentVerificationConfigRequest / DeploymentVerificationCheckRequest (#1023) plus the
 * semantic checks of #1024 that need no network (window / interval ranges, threshold reachable in
 * the window, unique names, http(s) URLs). Host policy (resolvability, restricted addresses, the
 * allow-list) is only decidable server-side and surfaces as 422 DEPLOYMENT_VERIFICATION_INVALID.
 */
export const VERIFICATION_LIMITS = {
  maxChecks: 10,
  nameMax: 64,
  urlMax: 2048,
  bodyContainsMax: 1024,
  queryMax: 4096,
  thresholdMin: 1,
  thresholdMax: 20,
  windowMinMinutes: 1,
  windowMaxMinutes: 1440,
  intervalMinMinutes: 0.5,
  expectedStatusMin: 100,
  expectedStatusMax: 599,
} as const;

export interface VerificationCheckFormValues {
  kind: VerificationCheckKind;
  name: string;
  url?: string | null;
  expected_status?: number | null;
  body_contains?: string | null;
  prometheus_url?: string | null;
  query?: string | null;
  comparator?: VerificationComparator | null;
  threshold?: number | null;
  no_data_is_failure?: boolean;
}

export interface VerificationFormValues {
  enabled: boolean;
  window_minutes: number | null;
  interval_minutes: number | null;
  failure_threshold: number | null;
  checks: VerificationCheckFormValues[];
}

export function defaultCheckValues(
  kind: VerificationCheckKind = 'HTTP_HEALTH',
): VerificationCheckFormValues {
  return {
    kind,
    name: '',
    url: null,
    expected_status: 200,
    body_contains: null,
    prometheus_url: null,
    query: null,
    comparator: 'LT',
    threshold: null,
    no_data_is_failure: true,
  };
}

export function defaultVerificationValues(): VerificationFormValues {
  return {
    enabled: false,
    window_minutes: 15,
    interval_minutes: 1,
    failure_threshold: 3,
    checks: [defaultCheckValues()],
  };
}

const ISO_DURATION = /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/;

/** Seconds in an ISO-8601 time-only duration (`PT1H30M`, `PT30S`); null when unparseable. */
export function isoDurationSeconds(value: string | null | undefined): number | null {
  if (!value) return null;
  const match = ISO_DURATION.exec(value);
  if (!match || value === 'PT') return null;
  const [, h, m, s] = match;
  return Number(h ?? 0) * 3600 + Number(m ?? 0) * 60 + Number(s ?? 0);
}

/** Minutes ⇄ ISO-8601: whole minutes stay `PTnM`, anything finer goes out in seconds. */
export function minutesToIso(minutes: number): string {
  const seconds = Math.round(minutes * 60);
  return seconds % 60 === 0 ? `PT${seconds / 60}M` : `PT${seconds}S`;
}

function isoToMinutes(value: string | null | undefined): number | null {
  const seconds = isoDurationSeconds(value);
  return seconds == null ? null : seconds / 60;
}

export function toVerificationFormValues(
  config: DeploymentVerificationConfig | null | undefined,
): VerificationFormValues {
  if (!config) return defaultVerificationValues();
  return {
    enabled: true,
    window_minutes: isoToMinutes(config.window),
    interval_minutes: isoToMinutes(config.sample_interval),
    failure_threshold: config.failure_threshold,
    checks: config.checks.map((check) => ({
      ...defaultCheckValues(check.kind),
      name: check.name,
      url: check.url ?? null,
      expected_status: check.expected_status ?? 200,
      body_contains: check.body_contains ?? null,
      prometheus_url: check.prometheus_url ?? null,
      query: check.query ?? null,
      comparator: check.comparator ?? 'LT',
      threshold: check.threshold ?? null,
      no_data_is_failure: check.no_data_is_failure ?? true,
    })),
  };
}

function blankToNull(value: string | null | undefined): string | null {
  return value == null || value.trim() === '' ? null : value.trim();
}

function toWireCheck(check: VerificationCheckFormValues): VerificationCheckInput {
  if (check.kind === 'HTTP_HEALTH') {
    return {
      kind: 'HTTP_HEALTH',
      name: check.name.trim(),
      url: (check.url ?? '').trim(),
      expected_status: check.expected_status ?? null,
      body_contains: blankToNull(check.body_contains),
    };
  }
  return {
    kind: 'PROMQL_THRESHOLD',
    name: check.name.trim(),
    prometheus_url: (check.prometheus_url ?? '').trim(),
    query: (check.query ?? '').trim(),
    comparator: check.comparator ?? 'LT',
    threshold: check.threshold ?? 0,
    no_data_is_failure: check.no_data_is_failure ?? true,
  };
}

/** Null when the switch is off — the caller sends `null` / `clear_verification: true`. */
export function toVerificationWire(
  values: VerificationFormValues | undefined,
): DeploymentVerificationConfigInput | null {
  if (!values?.enabled) return null;
  return {
    window: minutesToIso(values.window_minutes ?? 0),
    sample_interval: minutesToIso(values.interval_minutes ?? 0),
    failure_threshold: values.failure_threshold ?? 0,
    checks: (values.checks ?? []).map(toWireCheck),
  };
}

export type VerificationUrlProblem = 'scheme' | 'invalid' | 'userinfo' | 'query';

/**
 * The network-free half of the server's URL rules (#1024): http(s) only, an absolute URL with a
 * host, no `user:password@`, and — for a Prometheus base URL, which gets `/api/v1/query`
 * appended — no query string or fragment. Resolution and the address policy stay server-side.
 */
export function verificationUrlProblem(
  value: string | null | undefined,
  options: { prometheus: boolean },
): VerificationUrlProblem | null {
  const trimmed = (value ?? '').trim();
  if (trimmed === '') return null;
  if (!/^https?:\/\//i.test(trimmed)) return 'scheme';
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return 'invalid';
  }
  if (url.hostname === '') return 'invalid';
  if (url.username !== '' || url.password !== '') return 'userinfo';
  if (options.prometheus && (trimmed.includes('?') || trimmed.includes('#'))) return 'query';
  return null;
}

/** The `reason` codes of 422 DEPLOYMENT_VERIFICATION_INVALID (docs/04-api-spec.md, #1024). */
export const VERIFICATION_INVALID_REASONS = [
  'window_out_of_range',
  'sample_interval_out_of_range',
  'failure_threshold_exceeds_samples',
  'duplicate_check_name',
  'invalid_url',
  'scheme_not_allowed',
  'userinfo_present',
  'host_missing',
  'host_unresolvable',
  'restricted_address',
  'metadata_endpoint',
  'host_not_allowed',
] as const;

export type VerificationInvalidReason = (typeof VERIFICATION_INVALID_REASONS)[number];

interface VerificationInvalidBody {
  error?: string;
  reason?: string;
  checkName?: string;
  check_name?: string;
}

/**
 * The server's `detail` for this 422 is one generic sentence; the actual rule is in `reason`
 * (and `checkName` for a per-check rule). Null when the body is not this error or the reason is
 * unknown, so the caller falls back to the generic path.
 */
export function verificationInvalidMessage(t: TFunction, body: unknown): string | null {
  if (body == null || typeof body !== 'object') return null;
  const problem = body as VerificationInvalidBody;
  if (problem.error !== 'DEPLOYMENT_VERIFICATION_INVALID') return null;
  const reason = VERIFICATION_INVALID_REASONS.find((r) => r === problem.reason);
  if (!reason) return null;
  const check = problem.checkName ?? problem.check_name ?? '—';
  return t(`deploygov.settings.verificationInvalid.${reason}`, { check });
}

/** How many samples the window can collect — the ceiling on `failure_threshold` (#1024). */
export function maxSamplesInWindow(
  windowMinutes: number | null | undefined,
  intervalMinutes: number | null | undefined,
): number | null {
  if (windowMinutes == null || intervalMinutes == null || intervalMinutes <= 0) return null;
  return Math.floor(Math.round(windowMinutes * 60) / Math.round(intervalMinutes * 60));
}

export function isDuplicateCheckName(
  checks: readonly (VerificationCheckFormValues | undefined)[] | undefined,
  index: number,
  name: string | null | undefined,
): boolean {
  const normalized = (name ?? '').trim();
  if (normalized === '') return false;
  return (checks ?? []).some(
    (check, i) => i !== index && (check?.name ?? '').trim() === normalized,
  );
}

/** "Verification: n checks" in the environments table; null when off. */
export function verificationCheckCount(config: DeploymentVerificationConfig | null | undefined) {
  return config ? config.checks.length : null;
}
