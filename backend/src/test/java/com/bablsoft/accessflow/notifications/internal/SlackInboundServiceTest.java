package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.internal.config.SlackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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
class SlackInboundServiceTest {

    private static final String SECRET = "topsecret-signing";

    @Mock DefaultSlackAppConfigService appConfigService;
    @Mock SlackReplayGuard replayGuard;
    @Mock SlackInteractionService interactionService;
    @Mock SlackLinkService linkService;

    private SlackInboundService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();
    private final DecryptedSlackApp app = new DecryptedSlackApp(orgId, "A1", "xoxb", SECRET, "C1");

    @BeforeEach
    void setUp() {
        var verifier = new SlackRequestVerifier(new SlackProperties(null, Duration.ofMinutes(5)));
        service = new SlackInboundService(JsonMapper.builder().build(), appConfigService,
                verifier, replayGuard, interactionService, linkService);
    }

    @Test
    void handleActionsDispatchesVerifiedPayload() {
        when(appConfigService.findActiveByAppId("A1")).thenReturn(Optional.of(app));
        when(replayGuard.firstSeen(anyString())).thenReturn(true);
        var json = "{\"api_app_id\":\"A1\",\"user\":{\"id\":\"U1\"},\"actions\":[{\"action_id\":\"approve\","
                + "\"value\":\"" + queryId + "\"}],\"response_url\":\"https://resp\"}";
        var body = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
        var ts = nowTs();

        var result = service.handleActions(body, ts, sign(ts, body));

        assertThat(result.status()).isEqualTo(200);
        verify(interactionService).handleAction(eq(app), eq("U1"), eq("approve"),
                eq(queryId.toString()), eq("https://resp"));
    }

    @Test
    void handleActionsRejectsMissingPayload() {
        var result = service.handleActions("nopayload=1", nowTs(), "v0=x");
        assertThat(result.status()).isEqualTo(401);
        verify(interactionService, never()).handleAction(any(), any(), any(), any(), any());
    }

    @Test
    void handleActionsRejectsUnknownApp() {
        when(appConfigService.findActiveByAppId(any())).thenReturn(Optional.empty());
        var json = "{\"api_app_id\":\"UNKNOWN\",\"actions\":[]}";
        var body = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
        var ts = nowTs();

        assertThat(service.handleActions(body, ts, sign(ts, body)).status()).isEqualTo(401);
    }

    @Test
    void handleActionsRejectsBadSignature() {
        when(appConfigService.findActiveByAppId("A1")).thenReturn(Optional.of(app));
        var json = "{\"api_app_id\":\"A1\",\"actions\":[]}";
        var body = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);

        assertThat(service.handleActions(body, nowTs(), "v0=deadbeef").status()).isEqualTo(401);
        verify(interactionService, never()).handleAction(any(), any(), any(), any(), any());
    }

    @Test
    void handleActionsRejectsReplay() {
        when(appConfigService.findActiveByAppId("A1")).thenReturn(Optional.of(app));
        when(replayGuard.firstSeen(anyString())).thenReturn(false);
        var json = "{\"api_app_id\":\"A1\",\"actions\":[{\"action_id\":\"approve\",\"value\":\"" + queryId + "\"}]}";
        var body = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
        var ts = nowTs();

        assertThat(service.handleActions(body, ts, sign(ts, body)).status()).isEqualTo(401);
        verify(interactionService, never()).handleAction(any(), any(), any(), any(), any());
    }

    @Test
    void handleActionsReturns200WhenNoActionsPresent() {
        when(appConfigService.findActiveByAppId("A1")).thenReturn(Optional.of(app));
        when(replayGuard.firstSeen(anyString())).thenReturn(true);
        var json = "{\"api_app_id\":\"A1\",\"actions\":[]}";
        var body = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
        var ts = nowTs();

        assertThat(service.handleActions(body, ts, sign(ts, body)).status()).isEqualTo(200);
        verify(interactionService, never()).handleAction(any(), any(), any(), any(), any());
    }

    @Test
    void handleCommandLinksAndReturnsEphemeral() {
        when(appConfigService.findActiveByAppId("A1")).thenReturn(Optional.of(app));
        when(replayGuard.firstSeen(anyString())).thenReturn(true);
        when(linkService.completeLink(orgId, "U1", "link CODE")).thenReturn("linked!");
        var body = "api_app_id=A1&user_id=U1&text=" + URLEncoder.encode("link CODE", StandardCharsets.UTF_8)
                + "&response_url=" + URLEncoder.encode("https://resp", StandardCharsets.UTF_8);
        var ts = nowTs();

        var result = service.handleCommand(body, ts, sign(ts, body));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.ephemeralText()).isEqualTo("linked!");
    }

    @Test
    void handleCommandRejectsUnknownApp() {
        when(appConfigService.findActiveByAppId(any())).thenReturn(Optional.empty());
        var body = "api_app_id=NOPE&user_id=U1&text=link+CODE";
        var ts = nowTs();

        assertThat(service.handleCommand(body, ts, sign(ts, body)).status()).isEqualTo(401);
    }

    private static String nowTs() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    private static String sign(String ts, String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var basestring = "v0:" + ts + ":" + body;
            return "v0=" + HexFormat.of().formatHex(mac.doFinal(basestring.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
