package com.bablsoft.accessflow.core.internal.secrets;

import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;

/**
 * Azure Key Vault store (AF-448). Fetches the latest version of the named secret from the
 * deployment-configured vault URL. Token acquisition and refresh are owned by the Azure
 * identity credential configured in {@link SecretsConfiguration}.
 */
class AzureKeyVaultSecretStore implements SecretStore {

    private final SecretClient secretClient;

    AzureKeyVaultSecretStore(SecretClient secretClient) {
        this.secretClient = secretClient;
    }

    @Override
    public String providerId() {
        return SecretReference.PROVIDER_AZURE;
    }

    @Override
    public String fetch(SecretReference reference) {
        KeyVaultSecret secret;
        try {
            secret = secretClient.getSecret(reference.path());
        } catch (RuntimeException ex) {
            throw new SecretStoreFetchException(
                    "Azure Key Vault read failed for " + reference.path(), ex);
        }
        if (secret == null || secret.getValue() == null) {
            throw new SecretStoreFetchException(
                    "Azure Key Vault secret " + reference.path() + " has no value");
        }
        return secret.getValue();
    }
}
