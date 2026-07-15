import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';

import {
  disabledSecretProvider,
  secretReferenceHelp,
  secretReferenceProvider,
  secretReferenceRule,
} from './secretReference';

const t = ((key: string, opts?: Record<string, unknown>) =>
  opts?.provider ? `${key}:${String(opts.provider)}` : key) as unknown as TFunction;

describe('secretReferenceProvider', () => {
  it('detects each provider prefix', () => {
    expect(secretReferenceProvider('vault:secret/prod/db#password')).toBe('vault');
    expect(secretReferenceProvider('aws:prod/db#password')).toBe('aws');
    expect(secretReferenceProvider('azure:db-password')).toBe('azure');
  });

  it('returns null for plain credentials and empty values', () => {
    expect(secretReferenceProvider('hunter2')).toBeNull();
    expect(secretReferenceProvider('')).toBeNull();
    expect(secretReferenceProvider(undefined)).toBeNull();
    expect(secretReferenceProvider(null)).toBeNull();
  });

  it('is case-sensitive like the backend', () => {
    expect(secretReferenceProvider('Vault:secret/db#password')).toBeNull();
    expect(secretReferenceProvider('AWS:prod/db')).toBeNull();
  });
});

describe('disabledSecretProvider', () => {
  it('flags a reference to a provider that is not enabled', () => {
    expect(disabledSecretProvider('azure:db-password', ['vault', 'aws'])).toBe('azure');
  });

  it('accepts a reference to an enabled provider', () => {
    expect(disabledSecretProvider('vault:secret/db#password', ['vault'])).toBeNull();
  });

  it('accepts plain credentials regardless of enabled providers', () => {
    expect(disabledSecretProvider('hunter2', [])).toBeNull();
    expect(disabledSecretProvider(undefined, [])).toBeNull();
  });
});

describe('secretReferenceHelp', () => {
  it('returns undefined when no provider is enabled', () => {
    expect(secretReferenceHelp([], t)).toBeUndefined();
  });

  it('joins the intro with one line per enabled provider', () => {
    expect(secretReferenceHelp(['vault', 'azure'], t)).toBe(
      'datasources.secret_ref.help_intro datasources.secret_ref.help_vault datasources.secret_ref.help_azure',
    );
  });
});

describe('secretReferenceRule', () => {
  it('resolves for plain credentials and enabled-provider references', async () => {
    const rule = secretReferenceRule(['vault'], t);
    await expect(rule.validator(undefined, 'hunter2')).resolves.toBeUndefined();
    await expect(rule.validator(undefined, 'vault:secret/db#password')).resolves.toBeUndefined();
    await expect(rule.validator(undefined, undefined)).resolves.toBeUndefined();
  });

  it('rejects references to disabled providers with the provider in the message', async () => {
    const rule = secretReferenceRule(['vault'], t);
    await expect(rule.validator(undefined, 'aws:prod/db')).rejects.toThrow(
      'datasources.secret_ref.provider_disabled:aws',
    );
  });
});
