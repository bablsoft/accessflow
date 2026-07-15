package com.bablsoft.accessflow.core.internal.secrets;

import com.bablsoft.accessflow.core.internal.config.SecretsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SecretsConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withUserConfiguration(PropertiesConfiguration.class, SecretsConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecretsProperties.class)
    static class PropertiesConfiguration {
    }

    @Test
    void noProvidersEnabledMeansNoStoreBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(SecretStore.class)).isEmpty();
        });
    }

    @Test
    void vaultTokenAuthCreatesVaultStore() {
        runner.withPropertyValues(
                        "accessflow.secrets.vault.enabled=true",
                        "accessflow.secrets.vault.uri=http://localhost:8200",
                        "accessflow.secrets.vault.token=dev-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SecretStore.class).values())
                            .singleElement()
                            .satisfies(store -> assertThat(store.providerId()).isEqualTo("vault"));
                });
    }

    @Test
    void vaultEnabledWithoutUriFailsFast() {
        runner.withPropertyValues(
                        "accessflow.secrets.vault.enabled=true",
                        "accessflow.secrets.vault.token=dev-token")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause().hasMessageContaining("ACCESSFLOW_SECRETS_VAULT_URI");
                });
    }

    @Test
    void vaultTokenAuthWithoutTokenFailsFast() {
        runner.withPropertyValues(
                        "accessflow.secrets.vault.enabled=true",
                        "accessflow.secrets.vault.uri=http://localhost:8200")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause().hasMessageContaining("ACCESSFLOW_SECRETS_VAULT_TOKEN");
                });
    }

    @Test
    void vaultAppRoleAuthCreatesVaultStore() {
        runner.withPropertyValues(
                        "accessflow.secrets.vault.enabled=true",
                        "accessflow.secrets.vault.uri=http://localhost:8200",
                        "accessflow.secrets.vault.auth-method=APPROLE",
                        "accessflow.secrets.vault.app-role-id=role",
                        "accessflow.secrets.vault.app-role-secret-id=secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SecretStore.class)).hasSize(1);
                });
    }

    @Test
    void vaultAppRoleAuthWithoutSecretIdFailsFast() {
        runner.withPropertyValues(
                        "accessflow.secrets.vault.enabled=true",
                        "accessflow.secrets.vault.uri=http://localhost:8200",
                        "accessflow.secrets.vault.auth-method=APPROLE",
                        "accessflow.secrets.vault.app-role-id=role")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause().hasMessageContaining("APP_ROLE_SECRET_ID");
                });
    }

    @Test
    void vaultKubernetesAuthWithoutRoleFailsFast() {
        runner.withPropertyValues(
                        "accessflow.secrets.vault.enabled=true",
                        "accessflow.secrets.vault.uri=http://localhost:8200",
                        "accessflow.secrets.vault.auth-method=KUBERNETES")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause().hasMessageContaining("KUBERNETES_ROLE");
                });
    }

    @Test
    void vaultUnknownAuthMethodFailsFast() {
        runner.withPropertyValues(
                        "accessflow.secrets.vault.enabled=true",
                        "accessflow.secrets.vault.uri=http://localhost:8200",
                        "accessflow.secrets.vault.auth-method=LDAP")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause().hasMessageContaining("LDAP");
                });
    }

    @Test
    void awsEnabledCreatesAwsStore() {
        runner.withPropertyValues(
                        "accessflow.secrets.aws.enabled=true",
                        "accessflow.secrets.aws.region=eu-west-1",
                        "accessflow.secrets.aws.access-key-id=key",
                        "accessflow.secrets.aws.secret-access-key=secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SecretStore.class).values())
                            .singleElement()
                            .satisfies(store -> assertThat(store.providerId()).isEqualTo("aws"));
                });
    }

    @Test
    void awsAccessKeyWithoutSecretFailsFast() {
        runner.withPropertyValues(
                        "accessflow.secrets.aws.enabled=true",
                        "accessflow.secrets.aws.region=eu-west-1",
                        "accessflow.secrets.aws.access-key-id=key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause().hasMessageContaining("SECRET_ACCESS_KEY");
                });
    }

    @Test
    void azureClientSecretCredentialCreatesAzureStore() {
        runner.withPropertyValues(
                        "accessflow.secrets.azure.enabled=true",
                        "accessflow.secrets.azure.vault-url=https://example.vault.azure.net",
                        "accessflow.secrets.azure.tenant-id=tenant",
                        "accessflow.secrets.azure.client-id=client",
                        "accessflow.secrets.azure.client-secret=secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SecretStore.class).values())
                            .singleElement()
                            .satisfies(store -> assertThat(store.providerId()).isEqualTo("azure"));
                });
    }

    @Test
    void azureEnabledWithoutVaultUrlFailsFast() {
        runner.withPropertyValues("accessflow.secrets.azure.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause().hasMessageContaining("ACCESSFLOW_SECRETS_AZURE_VAULT_URL");
                });
    }

    @Test
    void azureClientIdWithoutSecretFailsFast() {
        runner.withPropertyValues(
                        "accessflow.secrets.azure.enabled=true",
                        "accessflow.secrets.azure.vault-url=https://example.vault.azure.net",
                        "accessflow.secrets.azure.client-id=client")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause().hasMessageContaining("ACCESSFLOW_SECRETS_AZURE_TENANT_ID");
                });
    }

    @Test
    void allThreeProvidersCanBeEnabledTogether() {
        runner.withPropertyValues(
                        "accessflow.secrets.vault.enabled=true",
                        "accessflow.secrets.vault.uri=http://localhost:8200",
                        "accessflow.secrets.vault.token=dev-token",
                        "accessflow.secrets.vault.namespace=team-a",
                        "accessflow.secrets.aws.enabled=true",
                        "accessflow.secrets.aws.region=eu-west-1",
                        "accessflow.secrets.aws.endpoint-override=http://localhost:4566",
                        "accessflow.secrets.aws.access-key-id=key",
                        "accessflow.secrets.aws.secret-access-key=secret",
                        "accessflow.secrets.azure.enabled=true",
                        "accessflow.secrets.azure.vault-url=https://example.vault.azure.net")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SecretStore.class).values())
                            .extracting(SecretStore::providerId)
                            .containsExactlyInAnyOrder("vault", "aws", "azure");
                });
    }
}
