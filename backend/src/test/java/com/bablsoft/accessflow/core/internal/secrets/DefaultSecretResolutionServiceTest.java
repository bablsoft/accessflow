package com.bablsoft.accessflow.core.internal.secrets;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.InvalidSecretReferenceException;
import com.bablsoft.accessflow.core.api.SecretProviderDisabledException;
import com.bablsoft.accessflow.core.api.SecretResolutionException;
import com.bablsoft.accessflow.core.events.SecretReferenceResolutionFailedEvent;
import com.bablsoft.accessflow.core.events.SecretReferenceResolvedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSecretResolutionServiceTest {

    private static final UUID DS_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock
    private SecretStore vaultStore;
    @Mock
    private CredentialEncryptionService encryptionService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private MessageSource messageSource;

    private DefaultSecretResolutionService service;

    @BeforeEach
    void setUp() {
        lenient().when(vaultStore.providerId()).thenReturn("vault");
        lenient().when(messageSource.getMessage(eq("error.secret_resolution_failed"), any(), any()))
                .thenAnswer(inv -> "resolution failed: " + ((Object[]) inv.getArgument(1))[0]);
        service = new DefaultSecretResolutionService(
                List.of(vaultStore), encryptionService, eventPublisher, messageSource);
    }

    @Test
    void nonReferenceFallsBackToAesDecryptionWithoutEvents() {
        when(encryptionService.decrypt("ciphertext")).thenReturn("plaintext");

        assertThat(service.resolve("ciphertext", DS_ID, ORG_ID)).isEqualTo("plaintext");

        verifyNoInteractions(eventPublisher);
        verify(vaultStore, never()).fetch(any());
    }

    @Test
    void referenceIsFetchedFromStoreAndSuccessEventPublished() {
        when(vaultStore.fetch(any())).thenReturn("s3cret");

        var value = service.resolve("vault:secret/prod/db#password", DS_ID, ORG_ID);

        assertThat(value).isEqualTo("s3cret");
        verifyNoInteractions(encryptionService);
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new SecretReferenceResolvedEvent(
                "vault", "vault:secret/prod/db#password", DS_ID, ORG_ID));
    }

    @Test
    void contextlessResolvePublishesEventWithNullIds() {
        when(vaultStore.fetch(any())).thenReturn("s3cret");

        service.resolve("vault:secret/prod/db#password");

        verify(eventPublisher).publishEvent(new SecretReferenceResolvedEvent(
                "vault", "vault:secret/prod/db#password", null, null));
    }

    @Test
    void resolvedValuesAreNeverCached() {
        when(vaultStore.fetch(any())).thenReturn("s3cret");

        service.resolve("vault:secret/prod/db#password", DS_ID, ORG_ID);
        service.resolve("vault:secret/prod/db#password", DS_ID, ORG_ID);

        verify(vaultStore, times(2)).fetch(any());
    }

    @Test
    void disabledProviderAtResolveTimeFailsWithEvent() {
        assertThatThrownBy(() -> service.resolve("aws:my-secret", DS_ID, ORG_ID))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessage("resolution failed: aws");

        verify(eventPublisher).publishEvent(new SecretReferenceResolutionFailedEvent(
                "aws", "aws:my-secret", DS_ID, ORG_ID, "provider not enabled"));
    }

    @Test
    void storeFetchFailureWrapsIntoResolutionExceptionWithEvent() {
        when(vaultStore.fetch(any())).thenThrow(new SecretStoreFetchException("boom"));

        assertThatThrownBy(() -> service.resolve("vault:secret/prod/db#password", DS_ID, ORG_ID))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessage("resolution failed: vault");

        verify(eventPublisher).publishEvent(new SecretReferenceResolutionFailedEvent(
                "vault", "vault:secret/prod/db#password", DS_ID, ORG_ID, "boom"));
    }

    @Test
    void malformedReferenceAtResolveTimeFailsWithEvent() {
        assertThatThrownBy(() -> service.resolve("vault:no-field", DS_ID, ORG_ID))
                .isInstanceOf(SecretResolutionException.class);

        verify(eventPublisher).publishEvent(new SecretReferenceResolutionFailedEvent(
                "vault", "vault:no-field", DS_ID, ORG_ID, "malformed reference"));
    }

    @Test
    void isReferenceDelegatesToPrefixDetection() {
        assertThat(service.isReference("vault:secret/db#password")).isTrue();
        assertThat(service.isReference("plain-password")).isFalse();
    }

    @Test
    void validateReferenceAcceptsEnabledProvider() {
        assertThatCode(() -> service.validateReference("vault:secret/prod/db#password"))
                .doesNotThrowAnyException();
    }

    @Test
    void validateReferenceRejectsMalformedReference() {
        assertThatThrownBy(() -> service.validateReference("vault:missing-field"))
                .isInstanceOf(InvalidSecretReferenceException.class);
    }

    @Test
    void validateReferenceRejectsDisabledProvider() {
        assertThatThrownBy(() -> service.validateReference("azure:db-password"))
                .isInstanceOf(SecretProviderDisabledException.class)
                .satisfies(ex -> assertThat(((SecretProviderDisabledException) ex).provider())
                        .isEqualTo("azure"));
    }

    @Test
    void enabledProvidersReportsStableOrder() {
        var aws = org.mockito.Mockito.mock(SecretStore.class);
        when(aws.providerId()).thenReturn("aws");
        var withTwo = new DefaultSecretResolutionService(
                List.of(aws, vaultStore), encryptionService, eventPublisher, messageSource);

        assertThat(withTwo.enabledProviders()).containsExactly("vault", "aws");
        assertThat(service.enabledProviders()).containsExactly("vault");
    }
}
