package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.OAuth2Spec;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ConfigService;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2ReconcilerTest {

    @Mock OAuth2ConfigService oauth2ConfigService;
    @InjectMocks OAuth2Reconciler reconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void appliesEachProvider() {
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(OAuth2ProviderType.GOOGLE, "g-id", "g-sec", null, null,
                        UserRoleType.REVIEWER, true),
                new OAuth2Spec(OAuth2ProviderType.GITHUB, "gh-id", "gh-sec", null, null,
                        UserRoleType.REVIEWER, true)));

        var captor = ArgumentCaptor.forClass(UpdateOAuth2ConfigCommand.class);
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE), captor.capture());
        assertThat(captor.getValue().clientId()).isEqualTo("g-id");
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.GITHUB),
                any(UpdateOAuth2ConfigCommand.class));
    }

    @Test
    void throwsWhenProviderMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(null, "x", "y", null, null, null, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void throwsWhenClientIdMissing() {
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(OAuth2ProviderType.GOOGLE, " ", "secret", null, null, null, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clientId");
    }

    @Test
    void defaultsActiveToTrueWhenSpecActiveNull() {
        when(oauth2ConfigService.update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE),
                any(UpdateOAuth2ConfigCommand.class))).thenAnswer(inv -> null);

        reconciler.reconcile(ORG_ID, List.of(
                new OAuth2Spec(OAuth2ProviderType.GOOGLE, "id", "sec", null, null, null, null)));

        var captor = ArgumentCaptor.forClass(UpdateOAuth2ConfigCommand.class);
        verify(oauth2ConfigService).update(eq(ORG_ID), eq(OAuth2ProviderType.GOOGLE), captor.capture());
        assertThat(captor.getValue().active()).isTrue();
    }
}
