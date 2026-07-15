package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.notifications.api.PushSubscriptionService;
import com.bablsoft.accessflow.notifications.api.PushSubscriptionService.PushSubscriptionCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushSubscriptionControllerTest {

    private PushSubscriptionService service;
    private PushSubscriptionController controller;
    private Authentication authentication;

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(PushSubscriptionService.class);
        controller = new PushSubscriptionController(service);
        authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(
                JwtClaims.forSystemRole(userId, "rev@acme.test", UserRoleType.REVIEWER, orgId, false));
    }

    @Test
    void vapidPublicKeyReturnsServiceValue() {
        when(service.vapidPublicKey()).thenReturn("PUB");

        assertThat(controller.vapidPublicKey().publicKey()).isEqualTo("PUB");
    }

    @Test
    void subscribeBuildsCommandFromBodyAndCaller() {
        var body = new PushSubscriptionRequest("https://push/abc",
                new PushSubscriptionRequest.Keys("p256", "auth"), "Chrome");

        controller.subscribe(body, authentication);

        var captor = ArgumentCaptor.forClass(PushSubscriptionCommand.class);
        verify(service).subscribe(captor.capture());
        var command = captor.getValue();
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.organizationId()).isEqualTo(orgId);
        assertThat(command.endpoint()).isEqualTo("https://push/abc");
        assertThat(command.p256dhKey()).isEqualTo("p256");
        assertThat(command.authKey()).isEqualTo("auth");
        assertThat(command.userAgent()).isEqualTo("Chrome");
    }

    @Test
    void unsubscribeDelegatesScopedToCaller() {
        controller.unsubscribe(new PushUnsubscribeRequest("https://push/abc"), authentication);

        verify(service).unsubscribe(userId, "https://push/abc");
    }
}
