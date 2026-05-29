package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.api.SlackAppConfigView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlackWebDtoTest {

    @Test
    void slackAppConfigResponseMapsFromView() {
        var id = UUID.randomUUID();
        var org = UUID.randomUUID();
        var now = Instant.now();
        var view = new SlackAppConfigView(id, org, "A1", "C1", true, true, false, now, now);

        var response = SlackAppConfigResponse.from(view);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.organizationId()).isEqualTo(org);
        assertThat(response.appId()).isEqualTo("A1");
        assertThat(response.defaultChannelId()).isEqualTo("C1");
        assertThat(response.active()).isTrue();
        assertThat(response.hasBotToken()).isTrue();
        assertThat(response.hasSigningSecret()).isFalse();
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(response.updatedAt()).isEqualTo(now);
    }

    @Test
    void upsertRequestMapsToCommand() {
        var request = new UpsertSlackAppConfigRequest("A1", "C1", "xoxb", "secret", true);
        var command = request.toCommand();

        assertThat(command.appId()).isEqualTo("A1");
        assertThat(command.defaultChannelId()).isEqualTo("C1");
        assertThat(command.botToken()).isEqualTo("xoxb");
        assertThat(command.signingSecret()).isEqualTo("secret");
        assertThat(command.active()).isTrue();
    }

    @Test
    void testSlackResponseFactories() {
        assertThat(TestSlackResponse.ok("good").status()).isEqualTo("OK");
        assertThat(TestSlackResponse.ok("good").detail()).isEqualTo("good");
        assertThat(TestSlackResponse.error("bad").status()).isEqualTo("ERROR");
        assertThat(TestSlackResponse.error("bad").detail()).isEqualTo("bad");
    }
}
