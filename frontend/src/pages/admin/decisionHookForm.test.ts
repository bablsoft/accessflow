import { describe, expect, it } from 'vitest';
import type { DecisionHook } from '@/types/api';
import {
  ORG_DEFAULT,
  TIMEOUT_DEFAULT_MS,
  toFormValues,
  toWriteRequest,
  toggleEnabledRequest,
} from './decisionHookForm';

const hook: DecisionHook = {
  id: 'dh-1',
  organization_id: 'org-1',
  datasource_id: 'ds-1',
  name: 'OPA',
  endpoint_url: 'https://opa.example.com',
  timeout_ms: 1500,
  include_sql: true,
  enabled: true,
  secret_configured: true,
  version: 2,
  created_at: '2026-09-28T10:00:00Z',
  updated_at: '2026-09-28T10:00:00Z',
};

describe('decisionHookForm', () => {
  it('starts a new hook as an enabled organization default without the SQL', () => {
    expect(toFormValues(null)).toEqual({
      name: '',
      scope: ORG_DEFAULT,
      endpoint_url: '',
      timeout_ms: TIMEOUT_DEFAULT_MS,
      include_sql: false,
      enabled: true,
    });
  });

  it('round-trips an existing hook and never pre-fills the secret', () => {
    const values = toFormValues(hook);
    expect(values.scope).toBe('ds-1');
    expect(values.secret).toBeUndefined();
    expect(toWriteRequest(values)).toEqual({
      name: 'OPA',
      datasource_id: 'ds-1',
      endpoint_url: 'https://opa.example.com',
      timeout_ms: 1500,
      include_sql: true,
      enabled: true,
    });
  });

  it('maps the organization default to a null datasource and trims text', () => {
    const request = toWriteRequest({
      ...toFormValues(null),
      name: '  Gate ',
      endpoint_url: ' https://x.example.com ',
      secret: 's'.repeat(32),
    });
    expect(request.datasource_id).toBeNull();
    expect(request.name).toBe('Gate');
    expect(request.endpoint_url).toBe('https://x.example.com');
    expect(request.secret).toBe('s'.repeat(32));
  });

  it('treats an organization-default hook without a datasource as the default scope', () => {
    expect(toFormValues({ ...hook, datasource_id: undefined }).scope).toBe(ORG_DEFAULT);
  });

  it('toggles enabled without sending a secret', () => {
    const request = toggleEnabledRequest(hook, false);
    expect(request.enabled).toBe(false);
    expect(request).not.toHaveProperty('secret');
  });
});
