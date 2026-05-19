package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.NotificationChannelSpec;
import com.bablsoft.accessflow.notifications.api.CreateNotificationChannelCommand;
import com.bablsoft.accessflow.notifications.api.NotificationChannelService;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationChannelView;
import com.bablsoft.accessflow.notifications.api.UpdateNotificationChannelCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationChannelReconcilerTest {

    @Mock NotificationChannelService notificationChannelService;
    @Mock BootstrapStateTracker stateTracker;
    @Spy SpecFingerprinter fingerprinter = new SpecFingerprinter();
    @InjectMocks NotificationChannelReconciler reconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void emptyListReturnsEmptyMap() {
        var result = reconciler.reconcile(ORG_ID, List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void createsChannelWhenNameNotFound() {
        var newId = UUID.randomUUID();
        when(notificationChannelService.list(ORG_ID)).thenReturn(List.of());
        when(notificationChannelService.create(any(CreateNotificationChannelCommand.class)))
                .thenAnswer(inv -> {
                    CreateNotificationChannelCommand cmd = inv.getArgument(0);
                    return new NotificationChannelView(newId, cmd.organizationId(),
                            cmd.channelType(), cmd.name(), cmd.config(), true, Instant.now());
                });

        var spec = new NotificationChannelSpec("ops-slack", NotificationChannelType.SLACK, true,
                Map.of("channel", "#ops"));

        var result = reconciler.reconcile(ORG_ID, List.of(spec));

        assertThat(result).containsEntry("ops-slack", newId);

        var captor = ArgumentCaptor.forClass(CreateNotificationChannelCommand.class);
        verify(notificationChannelService).create(captor.capture());
        assertThat(captor.getValue().channelType()).isEqualTo(NotificationChannelType.SLACK);
        assertThat(captor.getValue().config()).containsEntry("channel", "#ops");
    }

    @Test
    void updatesChannelWhenNameMatches() {
        var existingId = UUID.randomUUID();
        var existing = new NotificationChannelView(existingId, ORG_ID, NotificationChannelType.SLACK,
                "ops-slack", Map.of("channel", "#old"), true, Instant.now());
        when(notificationChannelService.list(ORG_ID)).thenReturn(List.of(existing));
        when(notificationChannelService.update(eq(existingId), eq(ORG_ID),
                any(UpdateNotificationChannelCommand.class)))
                .thenReturn(new NotificationChannelView(existingId, ORG_ID, NotificationChannelType.SLACK,
                        "ops-slack", Map.of("channel", "#new"), true, Instant.now()));

        var spec = new NotificationChannelSpec("ops-slack", NotificationChannelType.SLACK, true,
                Map.of("channel", "#new"));

        var result = reconciler.reconcile(ORG_ID, List.of(spec));

        assertThat(result).containsEntry("ops-slack", existingId);
        verify(notificationChannelService, never()).create(any());
    }

    @Test
    void skipsUpdateAndEventWhenFingerprintMatches() {
        var existingId = UUID.randomUUID();
        var existing = new NotificationChannelView(existingId, ORG_ID, NotificationChannelType.SLACK,
                "ops-slack", Map.of("channel", "#ops"), true, Instant.now());
        when(notificationChannelService.list(ORG_ID)).thenReturn(List.of(existing));
        when(fingerprinter.fingerprint(any())).thenReturn("matching-fp");
        when(stateTracker.findFingerprint(ORG_ID,
                BootstrapResourceType.NOTIFICATION_CHANNEL, existingId))
                .thenReturn(Optional.of("matching-fp"));

        reconciler.reconcile(ORG_ID, List.of(new NotificationChannelSpec("ops-slack",
                NotificationChannelType.SLACK, true, Map.of("channel", "#ops"))));

        verify(notificationChannelService, never()).update(any(), any(), any());
        verify(stateTracker, never()).recordFingerprintAndPublish(any(), any(), any(), any(), any());
    }

    @Test
    void publishesUpdateEventWhenFingerprintDiffers() {
        var existingId = UUID.randomUUID();
        var existing = new NotificationChannelView(existingId, ORG_ID, NotificationChannelType.SLACK,
                "ops-slack", Map.of("channel", "#old"), true, Instant.now());
        when(notificationChannelService.list(ORG_ID)).thenReturn(List.of(existing));
        when(stateTracker.findFingerprint(ORG_ID,
                BootstrapResourceType.NOTIFICATION_CHANNEL, existingId))
                .thenReturn(Optional.of("stale-fp"));
        when(notificationChannelService.update(eq(existingId), eq(ORG_ID),
                any(UpdateNotificationChannelCommand.class)))
                .thenReturn(new NotificationChannelView(existingId, ORG_ID, NotificationChannelType.SLACK,
                        "ops-slack", Map.of("channel", "#new"), true, Instant.now()));

        reconciler.reconcile(ORG_ID, List.of(new NotificationChannelSpec("ops-slack",
                NotificationChannelType.SLACK, true, Map.of("channel", "#new"))));

        var captor = ArgumentCaptor.forClass(BootstrapResourceUpsertedEvent.class);
        verify(stateTracker).recordFingerprintAndPublish(eq(ORG_ID),
                eq(BootstrapResourceType.NOTIFICATION_CHANNEL), eq(existingId),
                org.mockito.ArgumentMatchers.anyString(), captor.capture());
        assertThat(captor.getValue().changeKind()).isEqualTo(BootstrapChangeKind.UPDATE);
        assertThat(captor.getValue().resourceType()).isEqualTo(BootstrapResourceType.NOTIFICATION_CHANNEL);
        assertThat(captor.getValue().summaryMetadata()).containsEntry("channel_type", "SLACK");
    }

    @Test
    void throwsWhenNameMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new NotificationChannelSpec(null, NotificationChannelType.SLACK, true, Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
    }

    @Test
    void throwsWhenChannelTypeMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new NotificationChannelSpec("ops", null, true, Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channelType");
    }

    @Test
    void activeDefaultsToTrueWhenNull() {
        var existingId = UUID.randomUUID();
        var existing = new NotificationChannelView(existingId, ORG_ID, NotificationChannelType.SLACK,
                "ops", Map.of(), false, Instant.now());
        when(notificationChannelService.list(ORG_ID)).thenReturn(List.of(existing));
        when(notificationChannelService.update(eq(existingId), eq(ORG_ID),
                any(UpdateNotificationChannelCommand.class)))
                .thenAnswer(inv -> {
                    UpdateNotificationChannelCommand cmd = inv.getArgument(2);
                    assertThat(cmd.active()).isTrue();
                    return existing;
                });

        reconciler.reconcile(ORG_ID, List.of(new NotificationChannelSpec("ops",
                NotificationChannelType.SLACK, null, Map.of())));
    }
}
