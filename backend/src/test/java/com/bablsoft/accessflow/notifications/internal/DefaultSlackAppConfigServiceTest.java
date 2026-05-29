package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigNotFoundException;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigValidationException;
import com.bablsoft.accessflow.notifications.api.UpsertSlackAppConfigCommand;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.SlackAppConfigEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.SlackAppConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSlackAppConfigServiceTest {

    @Mock SlackAppConfigRepository repository;
    @Mock CredentialEncryptionService encryptionService;

    private DefaultSlackAppConfigService service;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultSlackAppConfigService(repository, encryptionService);
        lenient().when(encryptionService.encrypt(any())).thenAnswer(i -> "enc:" + i.getArgument(0));
        lenient().when(encryptionService.decrypt(any())).thenAnswer(i -> {
            String c = i.getArgument(0);
            return c.startsWith("enc:") ? c.substring(4) : c;
        });
        lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void getMapsToMaskedView() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity()));

        var view = service.get(orgId).orElseThrow();

        assertThat(view.appId()).isEqualTo("A123");
        assertThat(view.defaultChannelId()).isEqualTo("C1");
        assertThat(view.hasBotToken()).isTrue();
        assertThat(view.hasSigningSecret()).isTrue();
        assertThat(view.active()).isTrue();
    }

    @Test
    void getReturnsEmptyWhenAbsent() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        assertThat(service.get(orgId)).isEmpty();
    }

    @Test
    void upsertCreatesAndEncryptsSecrets() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        var view = service.upsert(orgId,
                new UpsertSlackAppConfigCommand("A1", "C1", "xoxb-bot", "sign-secret", true));

        assertThat(view.hasBotToken()).isTrue();
        var saved = captureSaved();
        assertThat(saved.getBotTokenEncrypted()).isEqualTo("enc:xoxb-bot");
        assertThat(saved.getSigningSecretEncrypted()).isEqualTo("enc:sign-secret");
        assertThat(saved.getAppId()).isEqualTo("A1");
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void upsertCreateRejectsMissingBotToken() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(orgId,
                new UpsertSlackAppConfigCommand("A1", "C1", null, "secret", true)))
                .isInstanceOf(SlackAppConfigValidationException.class);
    }

    @Test
    void upsertCreateRejectsMissingSigningSecret() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(orgId,
                new UpsertSlackAppConfigCommand("A1", "C1", "xoxb", UpsertSlackAppConfigCommand.MASKED, true)))
                .isInstanceOf(SlackAppConfigValidationException.class);
    }

    @Test
    void upsertCreateRejectsBlankAppId() {
        assertThatThrownBy(() -> service.upsert(orgId,
                new UpsertSlackAppConfigCommand("  ", "C1", "xoxb", "secret", true)))
                .isInstanceOf(SlackAppConfigValidationException.class);
    }

    @Test
    void upsertCreateRejectsBlankDefaultChannel() {
        assertThatThrownBy(() -> service.upsert(orgId,
                new UpsertSlackAppConfigCommand("A1", "", "xoxb", "secret", true)))
                .isInstanceOf(SlackAppConfigValidationException.class);
    }

    @Test
    void upsertUpdateKeepsSecretsWhenMaskedOrNull() {
        var existing = entity();
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(existing));

        service.upsert(orgId,
                new UpsertSlackAppConfigCommand("A999", "C999", UpsertSlackAppConfigCommand.MASKED, null, false));

        var saved = captureSaved();
        assertThat(saved.getBotTokenEncrypted()).isEqualTo("enc:bot");
        assertThat(saved.getSigningSecretEncrypted()).isEqualTo("enc:sign");
        assertThat(saved.getAppId()).isEqualTo("A999");
        assertThat(saved.getDefaultChannelId()).isEqualTo("C999");
        assertThat(saved.isActive()).isFalse();
    }

    @Test
    void upsertUpdateReencryptsNewSecrets() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity()));

        service.upsert(orgId,
                new UpsertSlackAppConfigCommand("A1", "C1", "new-bot", "new-secret", null));

        var saved = captureSaved();
        assertThat(saved.getBotTokenEncrypted()).isEqualTo("enc:new-bot");
        assertThat(saved.getSigningSecretEncrypted()).isEqualTo("enc:new-secret");
    }

    @Test
    void deleteRemovesExistingConfig() {
        var existing = entity();
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(existing));

        service.delete(orgId);

        verify(repository).delete(existing);
    }

    @Test
    void deleteThrowsWhenAbsent() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(orgId))
                .isInstanceOf(SlackAppConfigNotFoundException.class);
    }

    @Test
    void findActiveByOrgDecryptsWhenActive() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity()));

        var app = service.findActiveByOrg(orgId).orElseThrow();
        assertThat(app.botToken()).isEqualTo("bot");
        assertThat(app.signingSecret()).isEqualTo("sign");
        assertThat(app.defaultChannelId()).isEqualTo("C1");
    }

    @Test
    void findActiveByOrgEmptyWhenInactive() {
        var inactive = entity();
        inactive.setActive(false);
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(inactive));

        assertThat(service.findActiveByOrg(orgId)).isEmpty();
    }

    @Test
    void findActiveByAppIdDecryptsWhenActive() {
        when(repository.findByAppId("A123")).thenReturn(Optional.of(entity()));

        assertThat(service.findActiveByAppId("A123")).isPresent();
    }

    @Test
    void findActiveByAppIdEmptyForBlankOrInactive() {
        assertThat(service.findActiveByAppId(null)).isEmpty();
        assertThat(service.findActiveByAppId("  ")).isEmpty();
    }

    @Test
    void findDecryptedByOrgIgnoresActiveFlag() {
        var inactive = entity();
        inactive.setActive(false);
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(inactive));

        assertThat(service.findDecryptedByOrg(orgId)).isPresent();
    }

    private SlackAppConfigEntity captureSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(SlackAppConfigEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private SlackAppConfigEntity entity() {
        var e = new SlackAppConfigEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setAppId("A123");
        e.setBotTokenEncrypted("enc:bot");
        e.setSigningSecretEncrypted("enc:sign");
        e.setDefaultChannelId("C1");
        e.setActive(true);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }
}
