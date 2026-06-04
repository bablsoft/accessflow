package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.ai.api.LangfuseConfigService;
import com.bablsoft.accessflow.ai.api.LangfuseConfigView;
import com.bablsoft.accessflow.ai.api.UpdateLangfuseConfigCommand;
import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.LangfuseSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LangfuseReconcilerTest {

    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock LangfuseConfigService langfuseConfigService;
    @Mock BootstrapStateTracker stateTracker;
    @Spy SpecFingerprinter fingerprinter = new SpecFingerprinter();
    @InjectMocks LangfuseReconciler reconciler;

    @Test
    void skipsWhenSpecNull() {
        reconciler.reconcile(ORG_ID, null);
        verify(langfuseConfigService, never()).update(any(), any());
    }

    @Test
    void skipsWhenDisabled() {
        reconciler.reconcile(ORG_ID, new LangfuseSpec(false, "https://lf", "pk", "sk", true, false));
        verify(langfuseConfigService, never()).update(any(), any());
    }

    @Test
    void appliesWhenEnabledAndFingerprintDiffers() {
        when(langfuseConfigService.getOrDefault(ORG_ID)).thenReturn(view());
        when(langfuseConfigService.update(eq(ORG_ID), any())).thenReturn(view());

        reconciler.reconcile(ORG_ID, new LangfuseSpec(true, "https://lf.example.com", "pk", "sk", true, true));

        var cmd = ArgumentCaptor.forClass(UpdateLangfuseConfigCommand.class);
        verify(langfuseConfigService).update(eq(ORG_ID), cmd.capture());
        assertThat(cmd.getValue().enabled()).isTrue();
        assertThat(cmd.getValue().promptManagementEnabled()).isTrue();

        var event = ArgumentCaptor.forClass(BootstrapResourceUpsertedEvent.class);
        verify(stateTracker).recordFingerprintAndPublish(eq(ORG_ID),
                eq(BootstrapResourceType.LANGFUSE_CONFIG), eq(ORG_ID), anyString(), event.capture());
        assertThat(event.getValue().changeKind()).isEqualTo(BootstrapChangeKind.UPDATE);
    }

    @Test
    void defaultsBooleansWhenNull() {
        when(langfuseConfigService.getOrDefault(ORG_ID)).thenReturn(view());
        when(langfuseConfigService.update(eq(ORG_ID), any())).thenReturn(view());

        reconciler.reconcile(ORG_ID, new LangfuseSpec(true, "https://lf", "pk", "sk", null, null));

        var cmd = ArgumentCaptor.forClass(UpdateLangfuseConfigCommand.class);
        verify(langfuseConfigService).update(eq(ORG_ID), cmd.capture());
        assertThat(cmd.getValue().tracingEnabled()).isTrue();
        assertThat(cmd.getValue().promptManagementEnabled()).isFalse();
    }

    @Test
    void skipsUpdateWhenFingerprintMatches() {
        when(fingerprinter.fingerprint(any())).thenReturn("fp");
        when(stateTracker.findFingerprint(ORG_ID, BootstrapResourceType.LANGFUSE_CONFIG, ORG_ID))
                .thenReturn(Optional.of("fp"));

        reconciler.reconcile(ORG_ID, new LangfuseSpec(true, "https://lf", "pk", "sk", true, false));

        verify(langfuseConfigService, never()).update(any(), any());
    }

    private static LangfuseConfigView view() {
        return new LangfuseConfigView(UUID.randomUUID(), ORG_ID, true, "https://lf.example.com/",
                "pk", true, true, true, Instant.now(), Instant.now());
    }
}
