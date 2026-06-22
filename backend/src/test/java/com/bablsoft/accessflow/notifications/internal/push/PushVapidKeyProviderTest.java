package com.bablsoft.accessflow.notifications.internal.push;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.notifications.internal.config.PushProperties;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.PushVapidConfigEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.PushVapidConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PushVapidKeyProviderTest {

    private PushVapidConfigRepository repository;
    private CredentialEncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        repository = mock(PushVapidConfigRepository.class);
        encryptionService = mock(CredentialEncryptionService.class);
    }

    private static String publicBase64(java.security.KeyPair kp) {
        return WebPushCrypto.base64Url(WebPushCrypto.encodePublicKey((ECPublicKey) kp.getPublic()));
    }

    private static String privateBase64(java.security.KeyPair kp) {
        return WebPushCrypto.base64Url(WebPushCrypto.encodePrivateKey((ECPrivateKey) kp.getPrivate()));
    }

    @Test
    void usesEnvOverrideWithoutTouchingRepository() {
        var kp = WebPushCrypto.generateVapidKeyPair();
        var props = new PushProperties(publicBase64(kp), privateBase64(kp), "mailto:ops@acme.test");
        var provider = new PushVapidKeyProvider(repository, encryptionService, props);

        var material = provider.resolve();

        assertThat(material.publicKeyBase64Url()).isEqualTo(publicBase64(kp));
        assertThat(material.subject()).isEqualTo("mailto:ops@acme.test");
        verifyNoInteractions(repository, encryptionService);
    }

    @Test
    void loadsExistingPersistedKeypair() {
        var kp = WebPushCrypto.generateVapidKeyPair();
        var entity = new PushVapidConfigEntity();
        entity.setPublicKey(publicBase64(kp));
        entity.setPrivateKeyEncrypted("ENC");
        entity.setSubject("mailto:stored@acme.test");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC")).thenReturn(privateBase64(kp));
        var provider = new PushVapidKeyProvider(repository, encryptionService,
                new PushProperties(null, null, null));

        var material = provider.resolve();

        assertThat(material.publicKeyBase64Url()).isEqualTo(publicBase64(kp));
        assertThat(material.subject()).isEqualTo("mailto:stored@acme.test");
    }

    @Test
    void generatesAndPersistsWhenAbsent() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(encryptionService.encrypt(org.mockito.ArgumentMatchers.anyString())).thenReturn("ENC");
        var provider = new PushVapidKeyProvider(repository, encryptionService,
                new PushProperties(null, null, "mailto:gen@acme.test"));

        var material = provider.resolve();

        assertThat(material.publicKeyBase64Url()).isNotBlank();
        assertThat(material.subject()).isEqualTo("mailto:gen@acme.test");
        var captor = ArgumentCaptor.forClass(PushVapidConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPrivateKeyEncrypted()).isEqualTo("ENC");
        assertThat(captor.getValue().getPublicKey()).isEqualTo(material.publicKeyBase64Url());
    }
}
