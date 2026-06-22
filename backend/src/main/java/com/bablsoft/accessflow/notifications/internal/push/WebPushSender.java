package com.bablsoft.accessflow.notifications.internal.push;

import com.bablsoft.accessflow.notifications.internal.persistence.entity.PushSubscriptionEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/**
 * Encrypts and delivers a single {@link WebPushMessage} to a stored subscription via the W3C Web
 * Push protocol (AF-444): an {@code aes128gcm} body plus a VAPID {@code Authorization} header,
 * POSTed to the subscription endpoint. Failures are swallowed (logged) so one dead device never
 * affects the workflow; a {@code 404}/{@code 410} prunes the now-invalid subscription row.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebPushSender {

    private static final String TTL_SECONDS = "86400";

    private final RestClient restClient;
    private final PushVapidKeyProvider vapidKeyProvider;
    private final PushSubscriptionRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public void send(PushSubscriptionEntity subscription, WebPushMessage message) {
        try {
            var vapid = vapidKeyProvider.resolve();
            var payload = renderPayload(message);
            var body = WebPushCrypto.encrypt(payload, WebPushCrypto.decodeBase64Url(subscription.getP256dhKey()),
                    WebPushCrypto.decodeBase64Url(subscription.getAuthKey()));
            var authorization = WebPushCrypto.vapidAuthorization(subscription.getEndpoint(),
                    vapid.privateKey(), vapid.publicKey(), vapid.subject(), clock.instant());
            restClient.post()
                    .uri(URI.create(subscription.getEndpoint()))
                    .header("Authorization", authorization)
                    .header("Content-Encoding", "aes128gcm")
                    .header("TTL", TTL_SECONDS)
                    .header("Urgency", "high")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (isGone(ex.getStatusCode())) {
                log.info("Pruning expired push subscription {}", subscription.getId());
                deleteQuietly(subscription);
            } else {
                log.warn("Web Push delivery to subscription {} failed with status {}",
                        subscription.getId(), ex.getStatusCode());
            }
        } catch (RuntimeException ex) {
            log.error("Web Push delivery to subscription {} failed", subscription.getId(), ex);
        }
    }

    // repository.deleteById is itself transactional, so no class-level @Transactional is needed here.
    void deleteQuietly(PushSubscriptionEntity subscription) {
        try {
            repository.deleteById(subscription.getId());
        } catch (RuntimeException ex) {
            log.warn("Failed to prune push subscription {}", subscription.getId(), ex);
        }
    }

    private byte[] renderPayload(WebPushMessage message) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", message.title());
        root.put("body", message.body());
        ObjectNode data = root.putObject("data");
        data.put("url", message.url());
        if (message.queryId() != null) {
            data.put("queryId", message.queryId().toString());
        }
        ArrayNode actions = root.putArray("actions");
        actions.addObject().put("action", "approve").put("title", "Approve");
        actions.addObject().put("action", "reject").put("title", "Reject");
        return objectMapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isGone(HttpStatusCode status) {
        return status.value() == 404 || status.value() == 410;
    }

    public void sendAll(List<PushSubscriptionEntity> subscriptions, WebPushMessage message) {
        for (var subscription : subscriptions) {
            send(subscription, message);
        }
    }
}
