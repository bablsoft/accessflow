package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.notifications.internal.SlackLinkCodeStore;
import com.bablsoft.accessflow.notifications.internal.SlackLinkService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackLinkControllerTest {

    @Mock SlackLinkService linkService;

    private SlackLinkController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new SlackLinkController(linkService);
    }

    private UsernamePasswordAuthenticationToken auth() {
        var claims = JwtClaims.forSystemRole(userId, "rev@example.com", UserRoleType.REVIEWER, orgId);
        return new UsernamePasswordAuthenticationToken(claims, "n/a", List.of());
    }

    @Test
    void createLinkCodeReturns201WithCode() {
        var expiresAt = Instant.now();
        when(linkService.issueCode(userId)).thenReturn(new SlackLinkCodeStore.Issued("CODE123", expiresAt));

        var resp = controller.createLinkCode(auth());

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("CODE123");
        assertThat(resp.getBody().expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void linkStatusReportsLinked() {
        when(linkService.linkStatus(userId)).thenReturn(Optional.of("U123"));

        var status = controller.linkStatus(auth());

        assertThat(status.linked()).isTrue();
        assertThat(status.slackUserId()).isEqualTo("U123");
    }

    @Test
    void linkStatusReportsUnlinked() {
        when(linkService.linkStatus(userId)).thenReturn(Optional.empty());

        var status = controller.linkStatus(auth());

        assertThat(status.linked()).isFalse();
        assertThat(status.slackUserId()).isNull();
    }

    @Test
    void unlinkReturns204AndDelegates() {
        var resp = controller.unlink(auth());

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(linkService).unlink(userId);
    }
}
