package com.bablsoft.accessflow.core.internal.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultSecretStoreTest {

    private static final SecretReference REF =
            SecretReference.parse("vault:secret/prod/db#password");

    @Mock
    private VaultOperations vaultOperations;

    @Test
    void providerIdIsVault() {
        assertThat(new VaultSecretStore(vaultOperations, 2).providerId()).isEqualTo("vault");
    }

    @Test
    void kvV2ReadsDataPathAndExtractsNestedField() {
        var response = new VaultResponse();
        response.setData(Map.of("data", Map.of("password", "s3cret"),
                "metadata", Map.of("version", 1)));
        when(vaultOperations.read("secret/data/prod/db")).thenReturn(response);

        assertThat(new VaultSecretStore(vaultOperations, 2).fetch(REF)).isEqualTo("s3cret");
    }

    @Test
    void kvV1ReadsPlainPathAndExtractsField() {
        var response = new VaultResponse();
        response.setData(Map.of("password", "s3cret"));
        when(vaultOperations.read("secret/prod/db")).thenReturn(response);

        assertThat(new VaultSecretStore(vaultOperations, 1).fetch(REF)).isEqualTo("s3cret");
    }

    @Test
    void missingSecretThrows() {
        when(vaultOperations.read("secret/data/prod/db")).thenReturn(null);

        assertThatThrownBy(() -> new VaultSecretStore(vaultOperations, 2).fetch(REF))
                .isInstanceOf(SecretStoreFetchException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void kvV2ResponseWithoutInnerDataThrows() {
        var response = new VaultResponse();
        response.setData(Map.of("unexpected", "shape"));
        when(vaultOperations.read("secret/data/prod/db")).thenReturn(response);

        assertThatThrownBy(() -> new VaultSecretStore(vaultOperations, 2).fetch(REF))
                .isInstanceOf(SecretStoreFetchException.class);
    }

    @Test
    void missingFieldThrowsWithoutSecretValueInMessage() {
        var response = new VaultResponse();
        response.setData(Map.of("data", Map.of("other", "top-secret-value")));
        when(vaultOperations.read("secret/data/prod/db")).thenReturn(response);

        assertThatThrownBy(() -> new VaultSecretStore(vaultOperations, 2).fetch(REF))
                .isInstanceOf(SecretStoreFetchException.class)
                .hasMessageContaining("password")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("top-secret-value"));
    }

    @Test
    void readFailureIsWrapped() {
        when(vaultOperations.read("secret/data/prod/db")).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> new VaultSecretStore(vaultOperations, 2).fetch(REF))
                .isInstanceOf(SecretStoreFetchException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
