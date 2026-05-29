package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.model.block.LayoutBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Posts messages to Slack via {@code chat.postMessage} using a workspace bot token. Used both for
 * interactive review-request messages (when a Slack app is configured) and the admin "send test"
 * action. Failures surface as {@link NotificationDeliveryException}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlackBotMessenger {

    private final Slack slack;

    public void postMessage(String botToken, String channel, String fallbackText, List<LayoutBlock> blocks) {
        try {
            var response = slack.methods(botToken).chatPostMessage(req -> req
                    .channel(channel)
                    .text(fallbackText)
                    .blocks(blocks));
            if (!response.isOk()) {
                throw new NotificationDeliveryException(
                        "Slack chat.postMessage failed: " + response.getError());
            }
            log.debug("Posted Slack chat.postMessage to {} ({})", channel, response.getTs());
        } catch (SlackApiException ex) {
            throw new NotificationDeliveryException(
                    "Slack chat.postMessage failed: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new NotificationDeliveryException("Slack chat.postMessage delivery failed", ex);
        }
    }
}
