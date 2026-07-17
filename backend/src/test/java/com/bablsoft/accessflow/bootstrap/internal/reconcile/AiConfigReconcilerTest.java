package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.ai.api.AiConfigService;
import com.bablsoft.accessflow.ai.api.AiConfigView;
import com.bablsoft.accessflow.ai.api.CreateAiConfigCommand;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.AiConfigSpec;
import com.bablsoft.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
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
class AiConfigReconcilerTest {

    @Mock AiConfigService aiConfigService;
    @Mock BootstrapStateTracker stateTracker;
    @Spy SpecFingerprinter fingerprinter = new SpecFingerprinter();
    @InjectMocks AiConfigReconciler reconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void emptyListReturnsEmptyMap() {
        assertThat(reconciler.reconcile(ORG_ID, List.of())).isEmpty();
    }

    @Test
    void createsConfigWhenNameNotFound() {
        var newId = UUID.randomUUID();
        when(aiConfigService.list(ORG_ID)).thenReturn(List.of());
        when(aiConfigService.create(eq(ORG_ID), any(CreateAiConfigCommand.class)))
                .thenAnswer(inv -> view(newId, "claude", AiProviderType.ANTHROPIC));

        var spec = new AiConfigSpec("claude", AiProviderType.ANTHROPIC,
                "claude-sonnet-4", "", "sk-key", 30000, 4000, 1024, null, null, null);

        var result = reconciler.reconcile(ORG_ID, List.of(spec));

        assertThat(result).containsEntry("claude", newId);

        var captor = ArgumentCaptor.forClass(CreateAiConfigCommand.class);
        verify(aiConfigService).create(eq(ORG_ID), captor.capture());
        assertThat(captor.getValue().apiKey()).isEqualTo("sk-key");
        assertThat(captor.getValue().model()).isEqualTo("claude-sonnet-4");
    }

    @Test
    void updatesConfigWhenNameMatchesCaseInsensitively() {
        var existingId = UUID.randomUUID();
        when(aiConfigService.list(ORG_ID)).thenReturn(List.of(
                view(existingId, "Claude", AiProviderType.ANTHROPIC)));
        when(aiConfigService.update(eq(existingId), eq(ORG_ID), any(UpdateAiConfigCommand.class)))
                .thenReturn(view(existingId, "claude", AiProviderType.ANTHROPIC));

        var spec = new AiConfigSpec("claude", AiProviderType.ANTHROPIC,
                "claude-sonnet-4", null, "new-key", null, null, null, null, null, null);

        var result = reconciler.reconcile(ORG_ID, List.of(spec));

        assertThat(result).containsEntry("claude", existingId);
        verify(aiConfigService, never()).create(any(), any());
    }

    @Test
    void publishesCreateEventWithProviderMetadata() {
        var newId = UUID.randomUUID();
        when(aiConfigService.list(ORG_ID)).thenReturn(List.of());
        when(aiConfigService.create(eq(ORG_ID), any(CreateAiConfigCommand.class)))
                .thenAnswer(inv -> view(newId, "claude", AiProviderType.ANTHROPIC));

        reconciler.reconcile(ORG_ID, List.of(new AiConfigSpec("claude", AiProviderType.ANTHROPIC,
                "claude-sonnet-4", "", "sk-key", 30000, 4000, 1024, null, null, null)));

        var captor = ArgumentCaptor.forClass(BootstrapResourceUpsertedEvent.class);
        verify(stateTracker).recordFingerprintAndPublish(eq(ORG_ID),
                eq(BootstrapResourceType.AI_CONFIG), eq(newId),
                org.mockito.ArgumentMatchers.anyString(), captor.capture());
        assertThat(captor.getValue().resourceType()).isEqualTo(BootstrapResourceType.AI_CONFIG);
        assertThat(captor.getValue().changeKind()).isEqualTo(BootstrapChangeKind.CREATE);
        assertThat(captor.getValue().summaryMetadata()).containsEntry("provider", "ANTHROPIC");
    }

    @Test
    void skipsUpdateAndEventWhenFingerprintMatches() {
        var existingId = UUID.randomUUID();
        when(aiConfigService.list(ORG_ID)).thenReturn(List.of(
                view(existingId, "claude", AiProviderType.ANTHROPIC)));
        var spec = new AiConfigSpec("claude", AiProviderType.ANTHROPIC,
                "claude-sonnet-4", "https://api", "key", 30000, 4000, 1024, null, null, null);
        when(fingerprinter.fingerprint(any())).thenReturn("matching-fp");
        when(stateTracker.findFingerprint(ORG_ID, BootstrapResourceType.AI_CONFIG, existingId))
                .thenReturn(Optional.of("matching-fp"));

        reconciler.reconcile(ORG_ID, List.of(spec));

        verify(aiConfigService, never()).update(any(), any(), any());
        verify(stateTracker, never()).recordFingerprintAndPublish(any(), any(), any(), any(), any());
    }

    @Test
    void publishesUpdateEventWhenFingerprintDiffers() {
        var existingId = UUID.randomUUID();
        when(aiConfigService.list(ORG_ID)).thenReturn(List.of(
                view(existingId, "claude", AiProviderType.ANTHROPIC)));
        when(stateTracker.findFingerprint(ORG_ID, BootstrapResourceType.AI_CONFIG, existingId))
                .thenReturn(Optional.of("stale-fingerprint"));
        when(aiConfigService.update(eq(existingId), eq(ORG_ID), any(UpdateAiConfigCommand.class)))
                .thenReturn(view(existingId, "claude", AiProviderType.ANTHROPIC));

        reconciler.reconcile(ORG_ID, List.of(new AiConfigSpec("claude", AiProviderType.ANTHROPIC,
                "claude-sonnet-4-NEW", null, "key", 30000, 4000, 1024, null, null, null)));

        var captor = ArgumentCaptor.forClass(BootstrapResourceUpsertedEvent.class);
        verify(stateTracker).recordFingerprintAndPublish(eq(ORG_ID),
                eq(BootstrapResourceType.AI_CONFIG), eq(existingId),
                org.mockito.ArgumentMatchers.anyString(), captor.capture());
        assertThat(captor.getValue().changeKind()).isEqualTo(BootstrapChangeKind.UPDATE);
        assertThat(captor.getValue().changedFields()).contains("model");
    }

    @Test
    void throwsWhenNameMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new AiConfigSpec("", AiProviderType.ANTHROPIC, "m", null, null, null, null, null, null, null, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
    }

    @Test
    void throwsWhenProviderMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new AiConfigSpec("claude", null, "m", null, null, null, null, null, null, null, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void throwsWhenModelMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new AiConfigSpec("claude", AiProviderType.ANTHROPIC, " ", null, null, null, null, null, null, null, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
    }

    private AiConfigView view(UUID id, String name, AiProviderType provider) {
        return new AiConfigView(id, ORG_ID, name, provider, "claude-sonnet-4", "", true,
                30000, 4000, 1024, null, null, null,
                false, null, 4, 0.5, null, null, false, null, null, null, false,
                false, com.bablsoft.accessflow.core.api.VotingStrategy.WEIGHTED_AVERAGE, 1.0,
                java.util.List.of(), java.util.List.of(), null, 0,
                Instant.now(), Instant.now());
    }
}
