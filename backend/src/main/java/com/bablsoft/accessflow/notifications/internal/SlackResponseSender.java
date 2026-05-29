package com.bablsoft.accessflow.notifications.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;

/**
 * Posts a JSON payload to a Slack {@code response_url} — used to mutate the original interactive
 * message in place or to deliver an ephemeral reply. Delivery failures are swallowed (logged) so a
 * dead response_url never propagates back into the inbound request handling.
 */
@Component
@Slf4j
public class SlackResponseSender {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public SlackResponseSender(RestClient notificationsRestClient, ObjectMapper objectMapper) {
        this.restClient = notificationsRestClient;
        this.objectMapper = objectMapper;
    }

    public void send(String responseUrl, Map<String, Object> payload) {
        if (responseUrl == null || responseUrl.isBlank()) {
            return;
        }
        try {
            var json = objectMapper.writeValueAsString(payload);
            restClient.post()
                    .uri(URI.create(responseUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            log.warn("Failed to POST Slack response_url", ex);
        }
    }
}
