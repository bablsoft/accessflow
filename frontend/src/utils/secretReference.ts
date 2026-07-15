import type { TFunction } from 'i18next';
import type { SecretProvider } from '@/types/api';

/**
 * External secret-reference helpers (AF-448). Mirrors the backend's prefix detection: a
 * credential value starting with `vault:` / `aws:` / `azure:` (lowercase, case-sensitive) is a
 * secret reference resolved through the deployment's secret store instead of being stored as a
 * password.
 */
const REFERENCE_PREFIX = /^(vault|aws|azure):/;

/** The provider a value references, or null when it is a plain credential. */
export function secretReferenceProvider(value: string | undefined | null): SecretProvider | null {
  if (!value) return null;
  const match = REFERENCE_PREFIX.exec(value);
  return match ? (match[1] as SecretProvider) : null;
}

/**
 * Validation-parity check with the backend's write-path rule: a reference-shaped value whose
 * provider is not enabled must be rejected client-side (the server would answer
 * 400 SECRET_PROVIDER_DISABLED). Returns the offending provider, or null when the value is
 * acceptable (plain credential, or reference to an enabled provider).
 */
export function disabledSecretProvider(
  value: string | undefined | null,
  enabledProviders: readonly SecretProvider[],
): SecretProvider | null {
  const provider = secretReferenceProvider(value);
  if (provider && !enabledProviders.includes(provider)) return provider;
  return null;
}

const HELP_KEYS = {
  vault: 'datasources.secret_ref.help_vault',
  aws: 'datasources.secret_ref.help_aws',
  azure: 'datasources.secret_ref.help_azure',
} as const;

/**
 * `Form.Item extra` help for a credential field: intro plus one syntax line per enabled
 * provider. Undefined (no help shown) when no secret store is configured.
 */
export function secretReferenceHelp(
  enabledProviders: readonly SecretProvider[],
  t: TFunction,
): string | undefined {
  if (enabledProviders.length === 0) return undefined;
  const lines = enabledProviders.map((provider) => t(HELP_KEYS[provider]));
  return `${t('datasources.secret_ref.help_intro')} ${lines.join(' ')}`;
}

/**
 * Ant Design form rule mirroring the backend's SECRET_PROVIDER_DISABLED check: rejects a
 * reference-shaped value whose provider is not enabled in this deployment.
 */
export function secretReferenceRule(enabledProviders: readonly SecretProvider[], t: TFunction) {
  return {
    validator: (_rule: unknown, value: string | undefined) => {
      const provider = disabledSecretProvider(value, enabledProviders);
      return provider
        ? Promise.reject(new Error(t('datasources.secret_ref.provider_disabled', { provider })))
        : Promise.resolve();
    },
  };
}
