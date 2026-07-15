package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Resolves a stored datasource credential to plaintext at credential-use time (AF-448). A stored
 * value is either local AES-256-GCM ciphertext (decrypted via {@link CredentialEncryptionService})
 * or an external secret reference — {@code vault:<mount>/<path>#<field>},
 * {@code aws:<name-or-arn>[#jsonField]}, {@code azure:<secret-name>} — fetched from the
 * configured store on every resolve. Resolved plaintext is never cached or retained by the
 * resolver; callers must follow the same drop-after-pool-init discipline as with decryption.
 * Every external resolve (success or failure) is audited without the secret value.
 */
public interface SecretResolutionService {

    /**
     * Resolve without datasource context. Used where only the stored value is available — the
     * engine-plugin lane hands this method to {@link QueryEngineContext} as its
     * {@link CredentialDecryptor}. Audit rows for context-less resolves are attributed by
     * looking the reference up against the datasource table.
     */
    String resolve(String storedCredential);

    /**
     * Resolve with the owning datasource and organization, so audit rows carry full context.
     *
     * @throws SecretResolutionException when the reference cannot be resolved through its store
     */
    String resolve(String storedCredential, UUID datasourceId, UUID organizationId);

    /** Whether the value is shaped like a secret reference ({@code vault:}/{@code aws:}/{@code azure:} prefix). */
    boolean isReference(String value);

    /**
     * Validate a reference at datasource write time: syntax per provider and provider enabled.
     *
     * @throws InvalidSecretReferenceException  when the reference is malformed
     * @throws SecretProviderDisabledException when the provider is not enabled in this deployment
     */
    void validateReference(String value);

    /** Provider ids enabled in this deployment, in stable order ({@code vault}, {@code aws}, {@code azure}). */
    List<String> enabledProviders();
}
