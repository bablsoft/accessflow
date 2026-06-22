package com.bablsoft.accessflow.notifications.internal.push;

import com.bablsoft.accessflow.notifications.internal.persistence.entity.PushSubscriptionEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.PushSubscriptionRepository;
import com.bablsoft.accessflow.notifications.internal.push.PushVapidKeyProvider.VapidKeyMaterial;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebPushSenderTest {

    private MockRestServiceServer server;
    private PushVapidKeyProvider vapidKeyProvider;
    private PushSubscriptionRepository repository;
    private WebPushSender sender;

    private PushSubscriptionEntity subscription;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        vapidKeyProvider = mock(PushVapidKeyProvider.class);
        repository = mock(PushSubscriptionRepository.class);
        sender = new WebPushSender(builder.build(), vapidKeyProvider, repository,
                JsonMapper.builder().build(), Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"),
                ZoneOffset.UTC));

        var keyPair = WebPushCrypto.generateVapidKeyPair();
        when(vapidKeyProvider.resolve()).thenReturn(new VapidKeyMaterial(
                (ECPrivateKey) keyPair.getPrivate(),
                WebPushCrypto.encodePublicKey((ECPublicKey) keyPair.getPublic()),
                "PUB", "mailto:ops@acme.test"));

        // A valid (decodable) subscriber p256dh key is required for encryption to succeed.
        var subscriber = WebPushCrypto.generateVapidKeyPair();
        subscription = new PushSubscriptionEntity();
        subscription.setId(UUID.randomUUID());
        subscription.setEndpoint("https://fcm.googleapis.com/fcm/send/abc");
        subscription.setP256dhKey(WebPushCrypto.base64Url(
                WebPushCrypto.encodePublicKey((ECPublicKey) subscriber.getPublic())));
        subscription.setAuthKey(WebPushCrypto.base64Url(new byte[16]));
    }

    @Test
    void postsEncryptedBodyWithVapidAndAes128gcmHeaders() {
        server.expect(requestTo("https://fcm.googleapis.com/fcm/send/abc"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Encoding", "aes128gcm"))
                .andExpect(header("TTL", "86400"))
                .andExpect(header("Authorization", Matchers.startsWith("vapid t=")))
                .andRespond(withSuccess());

        sender.send(subscription, new WebPushMessage("Title", "Body",
                "https://app/reviews/x/decide", UUID.randomUUID()));

        server.verify();
    }

    @Test
    void prunesSubscriptionOnGone() {
        server.expect(requestTo("https://fcm.googleapis.com/fcm/send/abc"))
                .andRespond(withStatus(HttpStatus.GONE));

        sender.send(subscription, new WebPushMessage("Title", "Body", "https://app", null));

        verify(repository).deleteById(subscription.getId());
    }

    @Test
    void keepsSubscriptionOnTransientServerError() {
        server.expect(requestTo("https://fcm.googleapis.com/fcm/send/abc"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        sender.send(subscription, new WebPushMessage("Title", "Body", "https://app", null));

        verify(repository, never()).deleteById(subscription.getId());
    }
}
