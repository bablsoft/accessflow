package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.SystemSmtpSpec;
import com.bablsoft.accessflow.core.api.SaveSystemSmtpCommand;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemSmtpReconcilerTest {

    @Mock SystemSmtpService systemSmtpService;
    @InjectMocks SystemSmtpReconciler reconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void skipsWhenSpecNull() {
        reconciler.reconcile(ORG_ID, null);
        verify(systemSmtpService, never()).saveOrUpdate(any(), any());
    }

    @Test
    void skipsWhenDisabled() {
        reconciler.reconcile(ORG_ID, new SystemSmtpSpec(false, "h", 25, null, null, null, "f", null));
        verify(systemSmtpService, never()).saveOrUpdate(any(), any());
    }

    @Test
    void throwsWhenHostMissing() {
        var spec = new SystemSmtpSpec(true, "  ", 587, null, null, true, "from@x.y", null);
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host");
    }

    @Test
    void throwsWhenPortMissing() {
        var spec = new SystemSmtpSpec(true, "smtp.x.y", null, null, null, true, "from@x.y", null);
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port");
    }

    @Test
    void throwsWhenFromAddressMissing() {
        var spec = new SystemSmtpSpec(true, "smtp.x.y", 587, null, null, true, " ", null);
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fromAddress");
    }

    @Test
    void appliesSaveWhenEnabled() {
        when(systemSmtpService.saveOrUpdate(eq(ORG_ID), any(SaveSystemSmtpCommand.class)))
                .thenAnswer(inv -> null);

        var spec = new SystemSmtpSpec(true, "smtp.acme.com", 587, "noreply", "pw",
                true, "noreply@acme.com", "AccessFlow");

        reconciler.reconcile(ORG_ID, spec);

        var captor = ArgumentCaptor.forClass(SaveSystemSmtpCommand.class);
        verify(systemSmtpService).saveOrUpdate(eq(ORG_ID), captor.capture());
        assertThat(captor.getValue().host()).isEqualTo("smtp.acme.com");
        assertThat(captor.getValue().port()).isEqualTo(587);
        assertThat(captor.getValue().plaintextPassword()).isEqualTo("pw");
        assertThat(captor.getValue().tls()).isTrue();
    }

    @Test
    void tlsDefaultsToTrueWhenNull() {
        when(systemSmtpService.saveOrUpdate(eq(ORG_ID), any(SaveSystemSmtpCommand.class)))
                .thenAnswer(inv -> null);

        var spec = new SystemSmtpSpec(true, "smtp.acme.com", 587, null, null, null,
                "noreply@acme.com", null);

        reconciler.reconcile(ORG_ID, spec);

        var captor = ArgumentCaptor.forClass(SaveSystemSmtpCommand.class);
        verify(systemSmtpService).saveOrUpdate(eq(ORG_ID), captor.capture());
        assertThat(captor.getValue().tls()).isTrue();
    }
}
