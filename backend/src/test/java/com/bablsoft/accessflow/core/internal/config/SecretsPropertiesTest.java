package com.bablsoft.accessflow.core.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretsPropertiesTest {

    @Test
    void nullProviderBlocksDefaultToDisabled() {
        var properties = new SecretsProperties(null, null, null);

        assertThat(properties.vault().enabled()).isFalse();
        assertThat(properties.aws().enabled()).isFalse();
        assertThat(properties.azure().enabled()).isFalse();
    }

    @Test
    void vaultDefaultsApply() {
        var vault = new SecretsProperties.Vault(true, "http://localhost:8200", null, "t",
                null, null, null, null, 0, null);

        assertThat(vault.authMethod()).isEqualTo("TOKEN");
        assertThat(vault.kubernetesAuthPath()).isEqualTo("kubernetes");
        assertThat(vault.kvVersion()).isEqualTo(2);
    }

    @Test
    void vaultExplicitValuesAreKept() {
        var vault = new SecretsProperties.Vault(true, "http://localhost:8200", "APPROLE", null,
                "role", "secret", null, "k8s-custom", 1, "team-a");

        assertThat(vault.authMethod()).isEqualTo("APPROLE");
        assertThat(vault.kubernetesAuthPath()).isEqualTo("k8s-custom");
        assertThat(vault.kvVersion()).isEqualTo(1);
        assertThat(vault.namespace()).isEqualTo("team-a");
    }
}
