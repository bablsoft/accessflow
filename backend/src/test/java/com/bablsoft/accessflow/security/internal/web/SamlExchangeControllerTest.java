package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.AuthResult;
import com.bablsoft.accessflow.security.api.AuthenticationService;
import com.bablsoft.accessflow.security.internal.saml.SamlExchangeCodeStore;
import com.bablsoft.accessflow.security.internal.web.model.SamlExchangeRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlExchangeControllerTest {

    @Mock SamlExchangeCodeStore exchangeCodeStore;
    @Mock AuthenticationService authenticationService;
    @Mock RefreshCookieWriter refreshCookieWriter;
    @Mock MessageSource messageSource;
    @Mock HttpServletResponse response;

    private final UUID userId = UUID.randomUUID();

    @Test
    void exchangeIssuesTokensAndSetsRefreshCookie() {
        var controller = new SamlExchangeController(exchangeCodeStore, authenticationService,
                refreshCookieWriter, messageSource);
        when(exchangeCodeStore.consume("good")).thenReturn(Optional.of(userId));
        var result = new AuthResult("ACCESS_TOKEN", "REFRESH_TOKEN", "Bearer", 900L,
                view(userId, "alice@example.com"));
        when(authenticationService.issueForUser(userId)).thenReturn(result);

        var response201 = controller.exchange(new SamlExchangeRequest("good"), response);

        assertThat(response201.getStatusCode().value()).isEqualTo(200);
        assertThat(response201.getBody().accessToken()).isEqualTo("ACCESS_TOKEN");
        assertThat(response201.getBody().user().email()).isEqualTo("alice@example.com");
        assertThat(response201.getBody().user().authProvider()).isEqualTo("SAML");
        verify(refreshCookieWriter).write(eq(response), eq("REFRESH_TOKEN"), anyInt());
    }

    @Test
    void exchangeReturns401WhenCodeUnknown() {
        var controller = new SamlExchangeController(exchangeCodeStore, authenticationService,
                refreshCookieWriter, messageSource);
        when(exchangeCodeStore.consume("missing")).thenReturn(Optional.empty());
        when(messageSource.getMessage(eq("error.saml.exchange_code_invalid"), any(),
                eq(LocaleContextHolder.getLocale()))).thenReturn("Invalid");

        assertThatThrownBy(() -> controller.exchange(new SamlExchangeRequest("missing"), response))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid");
    }

    private UserView view(UUID id, String email) {
        return new UserView(id, email, "Display", UserRoleType.ANALYST, UUID.randomUUID(), true,
                AuthProviderType.SAML, null, null, "en", false, Instant.now());
    }
}
