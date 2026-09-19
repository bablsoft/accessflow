import { describe, expect, it } from 'vitest';
import i18n from '@/i18n';
import type { ServiceAccount } from '@/types/api';
import {
  CREATE_FORM_CONSTRAINTS,
  KEY_FORM_CONSTRAINTS,
  allowedToolCount,
  createInputFromForm,
  fieldRules,
  gracePeriodOf,
  isBootstrapManaged,
  keyStatus,
  limitsUpdateInput,
  overviewFormFromAccount,
  overviewUpdateInput,
  toolsFormFromAccount,
  toolsUpdateInput,
} from '../serviceAccountForm';

const t = i18n.t.bind(i18n);

function account(partial: Partial<ServiceAccount> = {}): ServiceAccount {
  return {
    id: 'sa-1',
    email: 'bot@example.com',
    display_name: 'Bot',
    role: 'READONLY',
    role_id: 'r-1',
    role_name: 'READONLY',
    active: true,
    managed_by: 'UI',
    description: null,
    owner_user_id: null,
    owner_email: null,
    owner_display_name: null,
    mcp_tool_allow_list: null,
    rate_limit_per_minute: null,
    rate_limit_per_day: null,
    active_api_key_count: 0,
    last_used_at: null,
    last_login_at: null,
    created_at: '2026-09-10T12:00:00Z',
    updated_at: '2026-09-10T12:00:00Z',
    api_keys: [],
    ...partial,
  };
}

describe('fieldRules', () => {
  it('derives AntD rules from a constraint table', () => {
    expect(fieldRules(t, CREATE_FORM_CONSTRAINTS.email)).toEqual([
      { required: true, whitespace: true, message: 'This field is required.' },
      { type: 'email', message: 'Enter a valid email address.' },
      { max: 255, message: 'Must be at most 255 characters.' },
    ]);
    expect(fieldRules(t, CREATE_FORM_CONSTRAINTS.rate_limit_per_day)).toEqual([
      { type: 'integer', min: 1, message: 'Must be a whole number greater than zero.' },
    ]);
    expect(fieldRules(t, KEY_FORM_CONSTRAINTS.name)).toHaveLength(2);
    expect(fieldRules(t, {})).toEqual([]);
  });
});

describe('MCP tools encoding', () => {
  it('reads null — or the absent key the non_null serializer sends — as every tool, and an array (even empty) as restricted', () => {
    expect(toolsFormFromAccount(account())).toEqual({ mode: 'ALL', tools: [] });
    expect(toolsFormFromAccount({})).toEqual({ mode: 'ALL', tools: [] });
    expect(allowedToolCount({}, ['a'])).toBeNull();
    expect(toolsFormFromAccount(account({ mcp_tool_allow_list: [] }))).toEqual({
      mode: 'RESTRICTED',
      tools: [],
    });
    expect(toolsFormFromAccount(account({ mcp_tool_allow_list: ['validate_sql'] }))).toEqual({
      mode: 'RESTRICTED',
      tools: ['validate_sql'],
    });
  });

  it('writes ALL as a clear and RESTRICTED as the list, an empty set included', () => {
    expect(toolsUpdateInput({ mode: 'ALL', tools: ['validate_sql'] })).toEqual({
      clear: ['MCP_TOOL_ALLOW_LIST'],
    });
    expect(toolsUpdateInput({ mode: 'RESTRICTED', tools: [] })).toEqual({ mcp_tool_allow_list: [] });
    expect(toolsUpdateInput({ mode: 'RESTRICTED', tools: ['submit_query'] })).toEqual({
      mcp_tool_allow_list: ['submit_query'],
    });
  });

  it('counts allowed tools against the catalog', () => {
    expect(allowedToolCount(account(), ['a', 'b'])).toBeNull();
    expect(allowedToolCount(account({ mcp_tool_allow_list: ['a'] }), ['a', 'b'])).toEqual({
      allowed: 1,
      total: 2,
    });
    expect(allowedToolCount(account({ mcp_tool_allow_list: [] }), undefined)).toEqual({
      allowed: 0,
      total: 0,
    });
  });
});

describe('limitsUpdateInput', () => {
  it('sends only changed values and clears a blanked field', () => {
    const current = account({ rate_limit_per_minute: 60, rate_limit_per_day: null });
    expect(limitsUpdateInput({ rate_limit_per_minute: 60 }, current)).toEqual({});
    expect(limitsUpdateInput({ rate_limit_per_minute: 30, rate_limit_per_day: 500 }, current)).toEqual({
      rate_limit_per_minute: 30,
      rate_limit_per_day: 500,
    });
    expect(limitsUpdateInput({ rate_limit_per_minute: null, rate_limit_per_day: undefined }, current)).toEqual({
      clear: ['RATE_LIMIT_PER_MINUTE'],
    });
    // The wire omits null limits entirely; an untouched blank form is still a no-op.
    expect(limitsUpdateInput({}, {})).toEqual({});
  });
});

describe('overview encoding', () => {
  it('maps an account onto the form', () => {
    expect(overviewFormFromAccount(account({ description: 'd', owner_user_id: 'u-1' }))).toEqual({
      display_name: 'Bot',
      role_id: 'r-1',
      owner_user_id: 'u-1',
      description: 'd',
      active: true,
    });
    expect(overviewFormFromAccount(account()).description).toBe('');
  });

  it('sends only what changed, clearing a blanked description or owner', () => {
    const current = account({ description: 'old', owner_user_id: 'u-1' });
    expect(overviewUpdateInput(overviewFormFromAccount(current), current)).toEqual({});
    expect(
      overviewUpdateInput(
        { display_name: ' Renamed ', role_id: 'r-2', owner_user_id: null, description: '  ', active: false },
        current,
      ),
    ).toEqual({
      display_name: 'Renamed',
      role_id: 'r-2',
      active: false,
      clear: ['OWNER_USER_ID', 'DESCRIPTION'],
    });
    expect(
      overviewUpdateInput(
        { display_name: 'Bot', role_id: 'r-1', owner_user_id: 'u-2', description: 'new', active: true },
        current,
      ),
    ).toEqual({ owner_user_id: 'u-2', description: 'new' });
  });

  it('treats absent owner / role keys like null', () => {
    const sparse = account();
    delete sparse.owner_user_id;
    delete sparse.role_id;
    expect(overviewFormFromAccount(sparse).owner_user_id).toBeNull();
    expect(
      overviewUpdateInput({ display_name: 'Bot', role_id: null, owner_user_id: null, description: '', active: true }, sparse),
    ).toEqual({});
  });

  it('never sends the declared fields of a bootstrap-managed account', () => {
    const bootstrap = account({ managed_by: 'BOOTSTRAP' });
    expect(isBootstrapManaged(bootstrap)).toBe(true);
    expect(isBootstrapManaged(account())).toBe(false);
    expect(
      overviewUpdateInput(
        { display_name: 'Renamed', role_id: 'r-9', owner_user_id: 'u-1', description: '', active: true },
        bootstrap,
      ),
    ).toEqual({ owner_user_id: 'u-1' });
  });

  it('ignores a blank display name instead of sending it', () => {
    expect(
      overviewUpdateInput({ display_name: '   ', role_id: 'r-1', description: '', active: true }, account()),
    ).toEqual({});
  });
});

describe('createInputFromForm', () => {
  it('trims, drops blanks and never sends the allow-list', () => {
    expect(
      createInputFromForm({
        email: ' ci@example.com ',
        display_name: ' CI ',
        role_id: null,
        owner_user_id: '',
        description: '  ',
        rate_limit_per_minute: null,
      }),
    ).toEqual({ email: 'ci@example.com', display_name: 'CI' });
    expect(
      createInputFromForm({
        email: 'ci@example.com',
        display_name: 'CI',
        role_id: 'r-1',
        owner_user_id: 'u-1',
        description: 'nightly',
        rate_limit_per_minute: 10,
        rate_limit_per_day: 100,
      }),
    ).toEqual({
      email: 'ci@example.com',
      display_name: 'CI',
      role_id: 'r-1',
      owner_user_id: 'u-1',
      description: 'nightly',
      rate_limit_per_minute: 10,
      rate_limit_per_day: 100,
    });
  });
});

describe('keys', () => {
  it('derives the key status from revocation then expiry', () => {
    const now = new Date('2026-09-19T00:00:00Z');
    expect(keyStatus({ revoked_at: '2026-09-01T00:00:00Z', expires_at: null }, now)).toBe('revoked');
    expect(keyStatus({ revoked_at: null, expires_at: '2026-09-18T00:00:00Z' }, now)).toBe('expired');
    expect(keyStatus({ revoked_at: null, expires_at: '2026-09-20T00:00:00Z' }, now)).toBe('active');
    expect(keyStatus({ revoked_at: null, expires_at: null })).toBe('active');
  });

  it('encodes the grace period as an ISO duration or keeps the default', () => {
    expect(gracePeriodOf(12)).toBe('PT12H');
    expect(gracePeriodOf(0)).toBeUndefined();
    expect(gracePeriodOf(null)).toBeUndefined();
    expect(gracePeriodOf(Number.NaN)).toBeUndefined();
  });
});
