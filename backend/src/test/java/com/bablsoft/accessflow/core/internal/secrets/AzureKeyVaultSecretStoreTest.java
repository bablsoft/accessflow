package com.bablsoft.accessflow.core.internal.secrets;

import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AzureKeyVaultSecretStoreTest {

    private static final SecretReference REF = SecretReference.parse("azure:prod-db-password");

    @Mock
    private SecretClient secretClient;

    @Test
    void providerIdIsAzure() {
        assertThat(new AzureKeyVaultSecretStore(secretClient).providerId()).isEqualTo("azure");
    }

    @Test
    void fetchesLatestSecretValueByName() {
        when(secretClient.getSecret("prod-db-password"))
                .thenReturn(new KeyVaultSecret("prod-db-password", "s3cret"));

        assertThat(new AzureKeyVaultSecretStore(secretClient).fetch(REF)).isEqualTo("s3cret");
    }

    @Test
    void missingValueThrows() {
        when(secretClient.getSecret("prod-db-password"))
                .thenReturn(new KeyVaultSecret("prod-db-password", null));

        assertThatThrownBy(() -> new AzureKeyVaultSecretStore(secretClient).fetch(REF))
                .isInstanceOf(SecretStoreFetchException.class)
                .hasMessageContaining("no value");
    }

    @Test
    void clientFailureIsWrapped() {
        when(secretClient.getSecret("prod-db-password"))
                .thenThrow(new IllegalStateException("403"));

        assertThatThrownBy(() -> new AzureKeyVaultSecretStore(secretClient).fetch(REF))
                .isInstanceOf(SecretStoreFetchException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
