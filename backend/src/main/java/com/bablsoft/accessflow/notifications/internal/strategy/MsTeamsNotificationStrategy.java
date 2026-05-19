package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.bablsoft.accessflow.notifications.internal.NotificationContext;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.MsTeamsChannelConfig;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
@Slf4j
class MsTeamsNotificationStrategy implements NotificationChannelStrategy {

    private final ChannelConfigCodec codec;
    private final MsTeamsPayloadFactory payloadFactory;
    private final RestClient restClient;

    @Override
    public NotificationChannelType supports() {
        return NotificationChannelType.MS_TEAMS;
    }

    @Override
    public void deliver(NotificationContext ctx, NotificationChannelEntity channel) {
        var config = codec.decodeMsTeams(channel.getConfigJson());
        var body = payloadFactory.buildEventBody(ctx);
        post(config, body, "event " + ctx.eventType());
    }

    @Override
    public void sendTest(NotificationChannelEntity channel, String optionalEmailOverride) {
        var config = codec.decodeMsTeams(channel.getConfigJson());
        var body = payloadFactory.buildTestBody();
        post(config, body, "test");
    }

    private void post(MsTeamsChannelConfig config, String body, String description) {
        try {
            restClient.post()
                    .uri(config.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Posted MS Teams {} to {}", description, config.webhookUrl());
        } catch (RestClientResponseException ex) {
            throw new NotificationDeliveryException(
                    "MS Teams delivery returned HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            throw new NotificationDeliveryException("MS Teams delivery failed", ex);
        }
    }
}
