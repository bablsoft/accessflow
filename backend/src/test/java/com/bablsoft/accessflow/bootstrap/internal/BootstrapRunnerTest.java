package com.bablsoft.accessflow.bootstrap.internal;

import com.bablsoft.accessflow.bootstrap.internal.reconcile.AdminUserReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.AiConfigReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.DatasourceReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.NotificationChannelReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.OAuth2Reconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.OrganizationReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.ReviewPlanReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.SamlReconciler;
import com.bablsoft.accessflow.bootstrap.internal.reconcile.SystemSmtpReconciler;
import com.bablsoft.accessflow.bootstrap.internal.spec.AdminSpec;
import com.bablsoft.accessflow.bootstrap.internal.spec.OrganizationSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapRunnerTest {

    @Mock OrganizationReconciler organizationReconciler;
    @Mock AdminUserReconciler adminUserReconciler;
    @Mock NotificationChannelReconciler notificationChannelReconciler;
    @Mock AiConfigReconciler aiConfigReconciler;
    @Mock ReviewPlanReconciler reviewPlanReconciler;
    @Mock DatasourceReconciler datasourceReconciler;
    @Mock SamlReconciler samlReconciler;
    @Mock OAuth2Reconciler oauth2Reconciler;
    @Mock SystemSmtpReconciler systemSmtpReconciler;

    @Test
    void skipsWhenDisabled() {
        var props = disabled();
        var runner = newRunner(props);

        runner.run();

        verifyNoInteractions(organizationReconciler, adminUserReconciler,
                notificationChannelReconciler, aiConfigReconciler, reviewPlanReconciler,
                datasourceReconciler, samlReconciler, oauth2Reconciler, systemSmtpReconciler);
    }

    @Test
    void executesReconcilersInTopologicalOrder() {
        var props = enabled();
        var orgId = UUID.randomUUID();
        when(organizationReconciler.reconcile(any())).thenReturn(orgId);
        when(notificationChannelReconciler.reconcile(any(), any())).thenReturn(Map.of());
        when(aiConfigReconciler.reconcile(any(), any())).thenReturn(Map.of());
        when(reviewPlanReconciler.reconcile(any(), any(), anyMap())).thenReturn(Map.of());

        newRunner(props).run();

        InOrder order = inOrder(organizationReconciler, adminUserReconciler,
                notificationChannelReconciler, aiConfigReconciler, reviewPlanReconciler,
                datasourceReconciler, samlReconciler, oauth2Reconciler, systemSmtpReconciler);
        order.verify(organizationReconciler).reconcile(any());
        order.verify(adminUserReconciler).reconcile(any(), any());
        order.verify(notificationChannelReconciler).reconcile(any(), any());
        order.verify(aiConfigReconciler).reconcile(any(), any());
        order.verify(reviewPlanReconciler).reconcile(any(), any(), anyMap());
        order.verify(datasourceReconciler).reconcile(any(), any(), anyMap(), anyMap());
        order.verify(samlReconciler).reconcile(any(), any());
        order.verify(oauth2Reconciler).reconcile(any(), any());
        order.verify(systemSmtpReconciler).reconcile(any(), any());
    }

    @Test
    void organizationFailureAbortsEarlyAndThrows() {
        var props = enabled();
        when(organizationReconciler.reconcile(any()))
                .thenThrow(new IllegalStateException("missing name"));

        assertThatThrownBy(() -> newRunner(props).run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("missing name");

        verifyNoInteractions(adminUserReconciler, notificationChannelReconciler,
                aiConfigReconciler, reviewPlanReconciler, datasourceReconciler,
                samlReconciler, oauth2Reconciler, systemSmtpReconciler);
    }

    @Test
    void collectsErrorsFromMultipleStepsThenThrowsOnce() {
        var props = enabled();
        when(organizationReconciler.reconcile(any())).thenReturn(UUID.randomUUID());
        when(notificationChannelReconciler.reconcile(any(), any())).thenReturn(Map.of());
        when(aiConfigReconciler.reconcile(any(), any())).thenReturn(Map.of());
        when(reviewPlanReconciler.reconcile(any(), any(), anyMap())).thenReturn(Map.of());

        org.mockito.Mockito.doThrow(new IllegalStateException("dsBoom"))
                .when(datasourceReconciler).reconcile(any(), any(), anyMap(), anyMap());
        org.mockito.Mockito.doThrow(new IllegalStateException("samlBoom"))
                .when(samlReconciler).reconcile(any(), any());

        assertThatThrownBy(() -> newRunner(props).run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("dsBoom")
                .hasMessageContaining("samlBoom");

        verify(oauth2Reconciler).reconcile(any(), any());
        verify(systemSmtpReconciler).reconcile(any(), any());
    }

    @Test
    void downstreamMapsAreEmptyWhenUpstreamReconcilerFails() {
        var props = enabled();
        when(organizationReconciler.reconcile(any())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new IllegalStateException("aiBoom"))
                .when(aiConfigReconciler).reconcile(any(), any());
        when(notificationChannelReconciler.reconcile(any(), any())).thenReturn(Map.of());
        when(reviewPlanReconciler.reconcile(any(), any(), anyMap())).thenReturn(Map.of());

        assertThatThrownBy(() -> newRunner(props).run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("aiBoom");

        verify(reviewPlanReconciler).reconcile(any(), any(), anyMap());
        verify(datasourceReconciler).reconcile(any(), any(), anyMap(), anyMap());
    }

    @Test
    void successfulRunDoesNotThrow() {
        var props = enabled();
        when(organizationReconciler.reconcile(any())).thenReturn(UUID.randomUUID());
        when(notificationChannelReconciler.reconcile(any(), any())).thenReturn(Map.of());
        when(aiConfigReconciler.reconcile(any(), any())).thenReturn(Map.of());
        when(reviewPlanReconciler.reconcile(any(), any(), anyMap())).thenReturn(Map.of());

        newRunner(props).run();

        verify(systemSmtpReconciler).reconcile(any(), any());
    }

    private BootstrapRunner newRunner(BootstrapProperties props) {
        return new BootstrapRunner(
                props,
                organizationReconciler,
                adminUserReconciler,
                notificationChannelReconciler,
                aiConfigReconciler,
                reviewPlanReconciler,
                datasourceReconciler,
                samlReconciler,
                oauth2Reconciler,
                systemSmtpReconciler);
    }

    private BootstrapProperties enabled() {
        return new BootstrapProperties(
                true,
                new OrganizationSpec("Acme", null),
                new AdminSpec("admin@acme.com", "Admin", "pw"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null);
    }

    private BootstrapProperties disabled() {
        return new BootstrapProperties(false, null, null, null, null, null, null, null, null, null);
    }
}
