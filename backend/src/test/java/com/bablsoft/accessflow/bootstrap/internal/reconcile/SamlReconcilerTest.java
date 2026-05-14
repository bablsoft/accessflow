package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.SamlSpec;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.SamlConfigService;
import com.bablsoft.accessflow.security.api.UpdateSamlConfigCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlReconcilerTest {

    @Mock SamlConfigService samlConfigService;
    @InjectMocks SamlReconciler reconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void skipsWhenSpecNull() {
        reconciler.reconcile(ORG_ID, null);
        verify(samlConfigService, never()).update(any(), any());
    }

    @Test
    void skipsWhenDisabled() {
        reconciler.reconcile(ORG_ID, new SamlSpec(false, null, null, null, null, null, null,
                null, null, null, null, null));
        verify(samlConfigService, never()).update(any(), any());
    }

    @Test
    void appliesUpdateWhenEnabled() {
        when(samlConfigService.update(eq(ORG_ID), any(UpdateSamlConfigCommand.class)))
                .thenAnswer(inv -> null);

        var spec = new SamlSpec(true, "https://idp/meta", "idp", "sp", "https://sp/acs",
                null, "cert", "email", "name", "role", UserRoleType.REVIEWER, true);

        reconciler.reconcile(ORG_ID, spec);

        var captor = ArgumentCaptor.forClass(UpdateSamlConfigCommand.class);
        verify(samlConfigService).update(eq(ORG_ID), captor.capture());
        assertThat(captor.getValue().idpMetadataUrl()).isEqualTo("https://idp/meta");
        assertThat(captor.getValue().signingCertPem()).isEqualTo("cert");
        assertThat(captor.getValue().defaultRole()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(captor.getValue().active()).isTrue();
    }

    @Test
    void defaultsActiveToTrueWhenSpecActiveNull() {
        var spec = new SamlSpec(true, "https://idp", "idp", "sp", "https://sp/acs",
                null, null, null, null, null, null, null);
        when(samlConfigService.update(eq(ORG_ID), any(UpdateSamlConfigCommand.class)))
                .thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, spec);

        var captor = ArgumentCaptor.forClass(UpdateSamlConfigCommand.class);
        verify(samlConfigService).update(eq(ORG_ID), captor.capture());
        assertThat(captor.getValue().active()).isTrue();
    }
}
