package com.bablsoft.accessflow.core.internal.secrets;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.SecretResolutionException;
import com.bablsoft.accessflow.core.events.SecretReferenceResolvedEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.testcontainers.vault.VaultContainer;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Live-path proof for AF-448 against a real HashiCorp Vault dev server: KV v2 read through the
 * spring-vault client, field extraction, and the full {@link DefaultSecretResolutionService}
 * resolve flow including the audit success event.
 */
class VaultSecretResolutionIntegrationTest {

    private static final String ROOT_TOKEN = "integration-test-root-token";

    private static final VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:2.0")
            .withVaultToken(ROOT_TOKEN)
            .withSecretInVault("secret/prod/db", "password=s3cret-from-vault", "username=svc");

    private static VaultTemplate vaultTemplate;

    @BeforeAll
    static void startVault() {
        VAULT.start();
        vaultTemplate = new VaultTemplate(
                VaultEndpoint.from(URI.create(VAULT.getHttpHostAddress())),
                new TokenAuthentication(ROOT_TOKEN));
    }

    @AfterAll
    static void stopVault() {
        VAULT.stop();
    }

    @Test
    void fetchesKvV2SecretField() {
        var store = new VaultSecretStore(vaultTemplate, 2);

        assertThat(store.fetch(SecretReference.parse("vault:secret/prod/db#password")))
                .isEqualTo("s3cret-from-vault");
        assertThat(store.fetch(SecretReference.parse("vault:secret/prod/db#username")))
                .isEqualTo("svc");
    }

    @Test
    void missingSecretAndMissingFieldFail() {
        var store = new VaultSecretStore(vaultTemplate, 2);

        assertThatThrownBy(() -> store.fetch(SecretReference.parse("vault:secret/nope#password")))
                .isInstanceOf(SecretStoreFetchException.class);
        assertThatThrownBy(() -> store.fetch(SecretReference.parse("vault:secret/prod/db#missing")))
                .isInstanceOf(SecretStoreFetchException.class);
    }

    @Test
    void resolutionServiceResolvesReferenceAndPublishesSuccessEvent() {
        var publisher = mock(ApplicationEventPublisher.class);
        var messageSource = mock(MessageSource.class);
        var encryption = mock(CredentialEncryptionService.class);
        var service = new DefaultSecretResolutionService(
                List.of(new VaultSecretStore(vaultTemplate, 2)), encryption, publisher,
                messageSource);
        var datasourceId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();

        var value = service.resolve("vault:secret/prod/db#password", datasourceId, organizationId);

        assertThat(value).isEqualTo("s3cret-from-vault");
        verify(publisher).publishEvent(new SecretReferenceResolvedEvent(
                "vault", "vault:secret/prod/db#password", datasourceId, organizationId));
    }

    @Test
    void resolutionServiceWrapsMissingSecretIntoResolutionException() {
        var publisher = mock(ApplicationEventPublisher.class);
        var messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(eq("error.secret_resolution_failed"), any(), any()))
                .thenReturn("resolution failed");
        var service = new DefaultSecretResolutionService(
                List.of(new VaultSecretStore(vaultTemplate, 2)),
                mock(CredentialEncryptionService.class), publisher, messageSource);

        assertThatThrownBy(() -> service.resolve("vault:secret/nope#password", null, null))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessage("resolution failed");
    }
}
