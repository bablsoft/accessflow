package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.notifications.api.CreateNotificationChannelCommand;
import com.partqam.accessflow.notifications.api.NotificationChannelConfigException;
import com.partqam.accessflow.notifications.api.NotificationChannelNotFoundException;
import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.UpdateNotificationChannelCommand;
import com.partqam.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.partqam.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.partqam.accessflow.notifications.internal.strategy.NotificationChannelStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultNotificationChannelServiceTest {

    private NotificationChannelRepository repository;
    private ChannelConfigCodec codec;
    private NotificationChannelStrategy webhookStrategy;
    private DefaultNotificationChannelService service;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationChannelRepository.class);
        codec = mock(ChannelConfigCodec.class);
        webhookStrategy = mock(NotificationChannelStrategy.class);
        when(webhookStrategy.supports()).thenReturn(NotificationChannelType.WEBHOOK);
        service = new DefaultNotificationChannelService(repository, codec,
                List.of(webhookStrategy));

        when(repository.save(any(NotificationChannelEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(codec.encodeForPersistence(any(), any())).thenReturn("{\"encoded\":true}");
        when(codec.mergeForPersistence(any(), anyString(), any())).thenReturn("{\"merged\":true}");
        when(codec.decodeForApi(anyString())).thenReturn(Map.of("masked", "********"));
    }

    @Test
    void listMapsRepoEntriesToViews() {
        when(repository.findAllByOrganizationIdOrderByCreatedAtAsc(orgId))
                .thenReturn(List.of(channel(NotificationChannelType.WEBHOOK, "webhook")));

        var views = service.list(orgId);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).config()).containsKey("masked");
    }

    @Test
    void createPersistsChannelWithEncodedConfig() {
        var cmd = new CreateNotificationChannelCommand(orgId, NotificationChannelType.WEBHOOK,
                "Eng webhook", Map.of("url", "https://h.example/x", "secret", "topsecret"));

        var view = service.create(cmd);

        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.channelType()).isEqualTo(NotificationChannelType.WEBHOOK);
        assertThat(view.name()).isEqualTo("Eng webhook");
        verify(codec).encodeForPersistence(eq(NotificationChannelType.WEBHOOK), eq(cmd.config()));
    }

    @Test
    void createRejectsNullOrganizationId() {
        var cmd = new CreateNotificationChannelCommand(null, NotificationChannelType.WEBHOOK,
                "x", Map.of());
        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("organizationId");
    }

    @Test
    void createRejectsNullChannelType() {
        var cmd = new CreateNotificationChannelCommand(orgId, null, "x", Map.of());
        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("channelType");
    }

    @Test
    void createRejectsBlankName() {
        var cmd = new CreateNotificationChannelCommand(orgId, NotificationChannelType.WEBHOOK,
                "  ", Map.of());
        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("name");
    }

    @Test
    void createRejectsNullName() {
        var cmd = new CreateNotificationChannelCommand(orgId, NotificationChannelType.WEBHOOK,
                null, Map.of());
        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(NotificationChannelConfigException.class);
    }

    @Test
    void updateAppliesAllFieldsAndMergesConfig() {
        var existing = channel(NotificationChannelType.WEBHOOK, "old");
        when(repository.findByIdAndOrganizationId(existing.getId(), orgId))
                .thenReturn(Optional.of(existing));

        var cmd = new UpdateNotificationChannelCommand("new name",
                Map.of("secret", "rotated"), false);

        var view = service.update(existing.getId(), orgId, cmd);

        assertThat(view.name()).isEqualTo("new name");
        assertThat(view.active()).isFalse();
        verify(codec).mergeForPersistence(eq(NotificationChannelType.WEBHOOK),
                anyString(), eq(cmd.config()));
    }

    @Test
    void updateLeavesFieldsUntouchedWhenNullsProvided() {
        var existing = channel(NotificationChannelType.WEBHOOK, "old");
        when(repository.findByIdAndOrganizationId(existing.getId(), orgId))
                .thenReturn(Optional.of(existing));

        var cmd = new UpdateNotificationChannelCommand(null, null, null);
        var view = service.update(existing.getId(), orgId, cmd);
        assertThat(view.name()).isEqualTo("old");
        assertThat(view.active()).isTrue();
        verify(codec, never()).mergeForPersistence(any(), anyString(), any());
    }

    @Test
    void updateSkipsConfigMergeWhenMapEmpty() {
        var existing = channel(NotificationChannelType.WEBHOOK, "old");
        when(repository.findByIdAndOrganizationId(existing.getId(), orgId))
                .thenReturn(Optional.of(existing));

        var cmd = new UpdateNotificationChannelCommand("renamed", Map.of(), null);
        service.update(existing.getId(), orgId, cmd);
        verify(codec, never()).mergeForPersistence(any(), anyString(), any());
    }

    @Test
    void updateSkipsBlankName() {
        var existing = channel(NotificationChannelType.WEBHOOK, "kept");
        when(repository.findByIdAndOrganizationId(existing.getId(), orgId))
                .thenReturn(Optional.of(existing));

        var cmd = new UpdateNotificationChannelCommand("  ", null, null);
        var view = service.update(existing.getId(), orgId, cmd);
        assertThat(view.name()).isEqualTo("kept");
    }

    @Test
    void updateThrowsWhenChannelMissing() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndOrganizationId(id, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, orgId,
                new UpdateNotificationChannelCommand("x", null, null)))
                .isInstanceOf(NotificationChannelNotFoundException.class);
    }

    @Test
    void sendTestRoutesToMatchingStrategy() {
        var existing = channel(NotificationChannelType.WEBHOOK, "x");
        when(repository.findByIdAndOrganizationId(existing.getId(), orgId))
                .thenReturn(Optional.of(existing));

        service.sendTest(existing.getId(), orgId, "ops@example.com");

        verify(webhookStrategy).sendTest(eq(existing), eq("ops@example.com"));
    }

    @Test
    void sendTestThrowsWhenChannelMissing() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndOrganizationId(id, orgId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sendTest(id, orgId, null))
                .isInstanceOf(NotificationChannelNotFoundException.class);
    }

    @Test
    void sendTestThrowsConfigExceptionWhenNoStrategyForType() {
        var existing = channel(NotificationChannelType.SLACK, "x");
        when(repository.findByIdAndOrganizationId(existing.getId(), orgId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.sendTest(existing.getId(), orgId, null))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("SLACK");
    }

    private NotificationChannelEntity channel(NotificationChannelType type, String name) {
        var c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setOrganizationId(orgId);
        c.setChannelType(type);
        c.setName(name);
        c.setActive(true);
        c.setConfigJson("{\"encoded\":true}");
        c.setCreatedAt(Instant.now());
        return c;
    }
}
