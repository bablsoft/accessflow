import type { TFunction } from 'i18next';
import type { Rule } from 'antd/es/form';
import type {
  CreateServiceAccountInput,
  ServiceAccount,
  ServiceAccountClearableField,
  ServiceAccountKey,
  UpdateServiceAccountInput,
} from '@/types/api';

// ── Validation parity (#871) ──────────────────────────────────────────────────
//
// One constraint table per backend request record. `createFormParity.test.ts` reads the Java
// records and asserts these tables against their Bean Validation annotations field for field, so
// a constraint added on one side without the other fails the build. The AntD rules are derived
// from the tables — never hand-written beside a Form.Item.

export interface FieldConstraints {
  required?: boolean;
  email?: boolean;
  max?: number;
  /** `@Positive` — an integer strictly greater than zero. */
  positive?: boolean;
}

/** `CreateServiceAccountRequest` (#871). */
export const CREATE_FORM_CONSTRAINTS = {
  email: { required: true, email: true, max: 255 },
  display_name: { required: true, max: 255 },
  description: { max: 500 },
  rate_limit_per_minute: { positive: true },
  rate_limit_per_day: { positive: true },
} as const satisfies Record<string, FieldConstraints>;

/** `UpdateServiceAccountRequest` (#871) — every field optional, non-blank when sent. */
export const UPDATE_FORM_CONSTRAINTS = {
  display_name: { max: 255 },
  description: { max: 500 },
  rate_limit_per_minute: { positive: true },
  rate_limit_per_day: { positive: true },
} as const satisfies Record<string, FieldConstraints>;

/** `IssueServiceAccountKeyRequest` / `RotateServiceAccountKeyRequest` (#871). */
export const KEY_FORM_CONSTRAINTS = {
  name: { required: true, max: 100 },
  application_name: { max: 100 },
} as const satisfies Record<string, FieldConstraints>;

export function fieldRules(t: TFunction, constraints: FieldConstraints): Rule[] {
  const rules: Rule[] = [];
  if (constraints.required) {
    rules.push({
      required: true,
      whitespace: true,
      message: t('admin.service_accounts.validation.required'),
    });
  }
  if (constraints.email) {
    rules.push({ type: 'email', message: t('validation.email_invalid') });
  }
  if (constraints.max !== undefined) {
    rules.push({
      max: constraints.max,
      message: t('admin.service_accounts.validation.max_length', { max: constraints.max }),
    });
  }
  if (constraints.positive) {
    rules.push({
      type: 'integer',
      min: 1,
      message: t('admin.service_accounts.validation.positive_integer'),
    });
  }
  return rules;
}

// ── MCP tools (#872) ─────────────────────────────────────────────────────────
//
// The wire shape is three-valued: `null` = every tool, `[]` = none, else the allowed names. A PUT
// with `null`/omitted means "unchanged", so returning to "every tool" goes through `clear`.
// On a GET the null case arrives as an *absent* key (non_null serialization), hence `== null`.

export type ToolsMode = 'ALL' | 'RESTRICTED';

export interface ToolsFormValues {
  mode: ToolsMode;
  tools: string[];
}

export function toolsFormFromAccount(
  account: Pick<ServiceAccount, 'mcp_tool_allow_list'>,
): ToolsFormValues {
  return account.mcp_tool_allow_list == null
    ? { mode: 'ALL', tools: [] }
    : { mode: 'RESTRICTED', tools: [...account.mcp_tool_allow_list] };
}

export function toolsUpdateInput(values: ToolsFormValues): UpdateServiceAccountInput {
  return values.mode === 'ALL'
    ? { clear: ['MCP_TOOL_ALLOW_LIST'] }
    : { mcp_tool_allow_list: values.tools };
}

/** "8 / 12" for a restricted list, `null` for "every tool" (the caller renders the word). */
export function allowedToolCount(
  account: Pick<ServiceAccount, 'mcp_tool_allow_list'>,
  catalog: readonly string[] | undefined,
): { allowed: number; total: number } | null {
  if (account.mcp_tool_allow_list == null) return null;
  return { allowed: account.mcp_tool_allow_list.length, total: catalog?.length ?? 0 };
}

// ── Rate limits (#873) ───────────────────────────────────────────────────────

export interface LimitsFormValues {
  rate_limit_per_minute?: number | null;
  rate_limit_per_day?: number | null;
}

/**
 * A blank input means "back to the deployment default", which the API expresses as the field's
 * name in `clear` — `null` in the body would leave it unchanged. Untouched fields are omitted.
 */
export function limitsUpdateInput(
  values: LimitsFormValues,
  current: Pick<ServiceAccount, 'rate_limit_per_minute' | 'rate_limit_per_day'>,
): UpdateServiceAccountInput {
  const input: UpdateServiceAccountInput = {};
  const clear: ServiceAccountClearableField[] = [];
  const perMinute = values.rate_limit_per_minute ?? null;
  if (perMinute !== (current.rate_limit_per_minute ?? null)) {
    if (perMinute === null) clear.push('RATE_LIMIT_PER_MINUTE');
    else input.rate_limit_per_minute = perMinute;
  }
  const perDay = values.rate_limit_per_day ?? null;
  if (perDay !== (current.rate_limit_per_day ?? null)) {
    if (perDay === null) clear.push('RATE_LIMIT_PER_DAY');
    else input.rate_limit_per_day = perDay;
  }
  if (clear.length > 0) input.clear = clear;
  return input;
}

// ── Overview ─────────────────────────────────────────────────────────────────

export interface OverviewFormValues {
  display_name: string;
  role_id: string | null;
  owner_user_id?: string | null;
  description?: string | null;
  active: boolean;
}

export function overviewFormFromAccount(account: ServiceAccount): OverviewFormValues {
  return {
    display_name: account.display_name,
    role_id: account.role_id ?? null,
    owner_user_id: account.owner_user_id ?? null,
    description: account.description ?? '',
    active: account.active,
  };
}

export function isBootstrapManaged(account: Pick<ServiceAccount, 'managed_by'>): boolean {
  return account.managed_by === 'BOOTSTRAP';
}

/**
 * Only the fields that changed. A `BOOTSTRAP` account's declared fields (display name, role) are
 * never sent — the API answers 409 for a *changed* value and the form keeps them read-only, so
 * stripping them here is what makes an untouched submit a no-op rather than a round trip.
 */
export function overviewUpdateInput(
  values: OverviewFormValues,
  account: ServiceAccount,
): UpdateServiceAccountInput {
  const input: UpdateServiceAccountInput = {};
  const clear: ServiceAccountClearableField[] = [];
  if (!isBootstrapManaged(account)) {
    const displayName = values.display_name.trim();
    if (displayName && displayName !== account.display_name) input.display_name = displayName;
    if (values.role_id && values.role_id !== (account.role_id ?? null)) input.role_id = values.role_id;
  }
  const owner = values.owner_user_id ?? null;
  if (owner !== (account.owner_user_id ?? null)) {
    if (owner === null) clear.push('OWNER_USER_ID');
    else input.owner_user_id = owner;
  }
  const description = values.description?.trim() ?? '';
  if (description !== (account.description ?? '')) {
    if (description === '') clear.push('DESCRIPTION');
    else input.description = description;
  }
  if (values.active !== account.active) input.active = values.active;
  if (clear.length > 0) input.clear = clear;
  return input;
}

// ── Create ───────────────────────────────────────────────────────────────────

export interface CreateFormValues {
  email: string;
  display_name: string;
  role_id: string | null;
  owner_user_id?: string | null;
  description?: string | null;
  rate_limit_per_minute?: number | null;
  rate_limit_per_day?: number | null;
}

/** Trims text, drops blanks, and never sends the allow-list — a new account starts with every tool. */
export function createInputFromForm(values: CreateFormValues): CreateServiceAccountInput {
  const input: CreateServiceAccountInput = {
    email: values.email.trim(),
    display_name: values.display_name.trim(),
  };
  if (values.role_id) input.role_id = values.role_id;
  if (values.owner_user_id) input.owner_user_id = values.owner_user_id;
  const description = values.description?.trim();
  if (description) input.description = description;
  if (typeof values.rate_limit_per_minute === 'number') {
    input.rate_limit_per_minute = values.rate_limit_per_minute;
  }
  if (typeof values.rate_limit_per_day === 'number') {
    input.rate_limit_per_day = values.rate_limit_per_day;
  }
  return input;
}

// ── Keys ─────────────────────────────────────────────────────────────────────

export type KeyStatus = 'active' | 'revoked' | 'expired';

export function keyStatus(
  key: Pick<ServiceAccountKey, 'revoked_at' | 'expires_at'>,
  now: Date = new Date(),
): KeyStatus {
  if (key.revoked_at) return 'revoked';
  if (key.expires_at && new Date(key.expires_at).getTime() <= now.getTime()) return 'expired';
  return 'active';
}

/**
 * The replacement key's suggested name. The superseded key stays live through the grace window,
 * so the API refuses a name that collides with it (`SERVICE_ACCOUNT_KEY_NAME_CONFLICT`) — the
 * spec's own example is `github-actions` → `github-actions-2026-09`. Trimmed to the 100-char cap.
 */
export function suggestedRotationName(name: string, now: Date = new Date()): string {
  const stamp = now.toISOString().slice(0, 10);
  const base = name.replace(/-\d{4}-\d{2}-\d{2}$/, '');
  return `${base.slice(0, KEY_FORM_CONSTRAINTS.name.max - stamp.length - 1)}-${stamp}`;
}

/** The rotation grace as the API's ISO-8601 duration; `undefined` keeps the deployment default. */
export function gracePeriodOf(hours: number | null | undefined): string | undefined {
  if (typeof hours !== 'number' || !Number.isFinite(hours) || hours <= 0) return undefined;
  return `PT${hours}H`;
}
