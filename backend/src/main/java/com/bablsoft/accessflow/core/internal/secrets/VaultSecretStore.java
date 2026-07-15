package com.bablsoft.accessflow.core.internal.secrets;

import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;

import java.util.Map;

/**
 * HashiCorp Vault KV store (AF-448). Reads {@code <mount>/data/<path>} for KV v2 (value under
 * {@code data.data}) or {@code <mount>/<path>} for KV v1 (value under {@code data}), then
 * extracts the reference's mandatory {@code #field}. Token lifecycle (renewal, re-login) is
 * owned by the spring-vault session manager configured in {@link SecretsConfiguration}.
 */
class VaultSecretStore implements SecretStore {

    private final VaultOperations vaultOperations;
    private final int kvVersion;

    VaultSecretStore(VaultOperations vaultOperations, int kvVersion) {
        this.vaultOperations = vaultOperations;
        this.kvVersion = kvVersion;
    }

    @Override
    public String providerId() {
        return SecretReference.PROVIDER_VAULT;
    }

    @Override
    public String fetch(SecretReference reference) {
        String readPath = kvVersion == 2
                ? reference.vaultMount() + "/data/" + reference.vaultPath()
                : reference.path();
        VaultResponse response;
        try {
            response = vaultOperations.read(readPath);
        } catch (RuntimeException ex) {
            throw new SecretStoreFetchException("Vault read failed for " + readPath, ex);
        }
        if (response == null || response.getData() == null) {
            throw new SecretStoreFetchException("Vault secret not found at " + readPath);
        }
        Map<String, Object> data = response.getData();
        if (kvVersion == 2) {
            Object inner = data.get("data");
            if (!(inner instanceof Map<?, ?> innerMap)) {
                throw new SecretStoreFetchException("Vault secret not found at " + readPath);
            }
            return extractField(innerMap, reference);
        }
        return extractField(data, reference);
    }

    private String extractField(Map<?, ?> data, SecretReference reference) {
        Object value = data.get(reference.field());
        if (value == null) {
            throw new SecretStoreFetchException(
                    "Vault secret field '" + reference.field() + "' missing at " + reference.path());
        }
        return String.valueOf(value);
    }
}
