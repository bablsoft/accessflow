import type { DecisionHook, DecisionHookWriteRequest } from '@/types/api';

/** The select value standing for "no datasource" — the organization default hook. */
export const ORG_DEFAULT = '__org_default__';

// Parity with the backend request records (#945): timeout @Min(100) @Max(10000), secret
// @Size(min = 32, max = 512), name @Size(max = 255), endpoint_url @Size(max = 2048).
export const TIMEOUT_MIN_MS = 100;
export const TIMEOUT_MAX_MS = 10_000;
export const TIMEOUT_DEFAULT_MS = 2000;
export const SECRET_MIN = 32;
export const SECRET_MAX = 512;
export const NAME_MAX = 255;
export const URL_MAX = 2048;

export interface DecisionHookFormValues {
  name: string;
  scope: string;
  endpoint_url: string;
  timeout_ms: number;
  secret?: string;
  include_sql: boolean;
  enabled: boolean;
}

export function toFormValues(hook: DecisionHook | null): DecisionHookFormValues {
  if (!hook) {
    return {
      name: '',
      scope: ORG_DEFAULT,
      endpoint_url: '',
      timeout_ms: TIMEOUT_DEFAULT_MS,
      include_sql: false,
      enabled: true,
    };
  }
  return {
    name: hook.name,
    scope: hook.datasource_id ?? ORG_DEFAULT,
    endpoint_url: hook.endpoint_url,
    timeout_ms: hook.timeout_ms,
    include_sql: hook.include_sql,
    enabled: hook.enabled,
  };
}

/** An empty secret on edit means "keep the stored one", so it is left out of the body. */
export function toWriteRequest(values: DecisionHookFormValues): DecisionHookWriteRequest {
  const request: DecisionHookWriteRequest = {
    name: values.name.trim(),
    datasource_id: values.scope === ORG_DEFAULT ? null : values.scope,
    endpoint_url: values.endpoint_url.trim(),
    timeout_ms: values.timeout_ms,
    include_sql: values.include_sql,
    enabled: values.enabled,
  };
  if (values.secret) {
    request.secret = values.secret;
  }
  return request;
}

/** The list-level enable switch: a full replace that resends the hook without its secret. */
export function toggleEnabledRequest(hook: DecisionHook, enabled: boolean): DecisionHookWriteRequest {
  return toWriteRequest({ ...toFormValues(hook), enabled });
}
