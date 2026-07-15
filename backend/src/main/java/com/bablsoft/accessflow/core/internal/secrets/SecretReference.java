package com.bablsoft.accessflow.core.internal.secrets;

import com.bablsoft.accessflow.core.api.InvalidSecretReferenceException;

import java.util.regex.Pattern;

/**
 * Parsed form of an external secret reference (AF-448).
 *
 * <ul>
 *   <li>{@code vault:<mount>/<path>#<field>} — {@code path} is {@code <mount>/<path>} (KV v2
 *       inserts {@code /data/} at fetch time), {@code field} is mandatory (Vault secrets are
 *       maps).</li>
 *   <li>{@code aws:<name-or-arn>[#jsonField]} — {@code path} is the SecretsManager
 *       {@code SecretId} (plain name or full ARN; ARNs contain {@code :} but never {@code #}),
 *       {@code field} optionally selects a key of a JSON-object {@code SecretString}.</li>
 *   <li>{@code azure:<secret-name>} — {@code path} is the Key Vault secret name
 *       ({@code [0-9a-zA-Z-]{1,127}}); no field (Key Vault secrets are single values).</li>
 * </ul>
 *
 * <p>Detection relies on AES-GCM ciphertext being Base64 — it never contains {@code :}, so a
 * {@code vault:}/{@code aws:}/{@code azure:} prefix is unambiguous.
 */
record SecretReference(String provider, String path, String field) {

    static final String PROVIDER_VAULT = "vault";
    static final String PROVIDER_AWS = "aws";
    static final String PROVIDER_AZURE = "azure";

    // Lowercase and case-sensitive by design: docs and the frontend validator advertise the
    // exact prefixes, and a raw password that merely resembles one keeps working (it is
    // encrypted locally as before).
    private static final Pattern REFERENCE_PREFIX = Pattern.compile("^(vault|aws|azure):");
    private static final Pattern AZURE_SECRET_NAME = Pattern.compile("[0-9a-zA-Z-]{1,127}");

    /** Whether the stored value is shaped like a secret reference (prefix check only). */
    static boolean isReference(String value) {
        return value != null && REFERENCE_PREFIX.matcher(value).find();
    }

    /**
     * Parse and shape-validate a reference. Callers must have checked {@link #isReference}.
     *
     * @throws InvalidSecretReferenceException when the reference is malformed
     */
    static SecretReference parse(String raw) {
        if (!isReference(raw)) {
            throw new InvalidSecretReferenceException(raw);
        }
        int colon = raw.indexOf(':');
        String provider = raw.substring(0, colon);
        String remainder = raw.substring(colon + 1);
        return switch (provider) {
            case PROVIDER_VAULT -> parseVault(raw, remainder);
            case PROVIDER_AWS -> parseAws(raw, remainder);
            case PROVIDER_AZURE -> parseAzure(raw, remainder);
            default -> throw new InvalidSecretReferenceException(raw);
        };
    }

    private static SecretReference parseVault(String raw, String remainder) {
        int hash = remainder.lastIndexOf('#');
        if (hash <= 0 || hash == remainder.length() - 1) {
            throw new InvalidSecretReferenceException(raw);
        }
        String path = remainder.substring(0, hash);
        String field = remainder.substring(hash + 1);
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            throw new InvalidSecretReferenceException(raw);
        }
        return new SecretReference(PROVIDER_VAULT, path, field);
    }

    private static SecretReference parseAws(String raw, String remainder) {
        int hash = remainder.lastIndexOf('#');
        String secretId = hash >= 0 ? remainder.substring(0, hash) : remainder;
        String field = hash >= 0 ? remainder.substring(hash + 1) : null;
        if (secretId.isBlank() || (field != null && field.isBlank())) {
            throw new InvalidSecretReferenceException(raw);
        }
        return new SecretReference(PROVIDER_AWS, secretId, field);
    }

    private static SecretReference parseAzure(String raw, String remainder) {
        if (!AZURE_SECRET_NAME.matcher(remainder).matches()) {
            throw new InvalidSecretReferenceException(raw);
        }
        return new SecretReference(PROVIDER_AZURE, remainder, null);
    }

    /** Vault mount (text before the first {@code /} of {@link #path}). Vault references only. */
    String vaultMount() {
        return path.substring(0, path.indexOf('/'));
    }

    /** Vault secret path relative to the mount. Vault references only. */
    String vaultPath() {
        return path.substring(path.indexOf('/') + 1);
    }
}
