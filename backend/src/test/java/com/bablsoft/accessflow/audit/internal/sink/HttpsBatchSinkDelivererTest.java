package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpsBatchSinkDelivererTest {

    private static final String URL = "https://receiver.example.com/audit";
    private static final String SECRET = "hmac-secret";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AuditSinkConfigCodec codec =
            new AuditSinkConfigCodec(mapper, new ReversibleEncryption());
    private final AuditExportEventWriter eventWriter = new AuditExportEventWriter(mapper);

    private MockRestServiceServer server;
    private HttpsBatchSinkDeliverer deliverer;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        deliverer = new HttpsBatchSinkDeliverer(codec, eventWriter, builder.build());
    }

    private AuditSinkEntity sink() {
        var sink = new AuditSinkEntity();
        sink.setId(UUID.randomUUID());
        sink.setOrganizationId(UUID.randomUUID());
        sink.setName("https");
        sink.setType(AuditSinkType.HTTPS_BATCH);
        sink.setConfigJson(codec.encodeForPersistence(AuditSinkType.HTTPS_BATCH,
                Map.of("url", URL, "secret", SECRET)));
        return sink;
    }

    private AuditExportEvent event(String action) {
        return new AuditExportEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                action, "query_request", UUID.randomUUID(), "{}", "203.0.113.5", "ua/1",
                Instant.parse("2026-08-19T10:15:30.123456Z"), "aa", "bb");
    }

    @Test
    void typeIsHttpsBatch() {
        assertThat(deliverer.type()).isEqualTo(AuditSinkType.HTTPS_BATCH);
    }

    @Test
    void postsSignedJsonArrayWithDeliveryHeaders() {
        var captured = new AtomicReference<MockClientHttpRequest>();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set((MockClientHttpRequest) request))
                .andRespond(withSuccess());

        deliverer.deliver(sink(), List.of(event("QUERY_SUBMITTED"), event("QUERY_EXECUTED")));

        server.verify();
        var request = captured.get();
        var bodyBytes = request.getBodyAsBytes();

        // Signature is the HMAC-SHA256 of the exact bytes sent, hex, sha256= prefixed.
        var expectedSignature = "sha256=" + hmacHex(bodyBytes, SECRET);
        assertThat(request.getHeaders().getFirst("X-AccessFlow-Signature"))
                .isEqualTo(expectedSignature);
        assertThat(request.getHeaders().getFirst("X-AccessFlow-Event")).isEqualTo("audit.batch");
        var deliveryId = request.getHeaders().getFirst("X-AccessFlow-Delivery");
        assertThat(deliveryId).isNotBlank();
        assertThat(UUID.fromString(deliveryId)).isNotNull();

        var body = mapper.readTree(new String(bodyBytes, StandardCharsets.UTF_8));
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.get(0).get("action").asString()).isEqualTo("QUERY_SUBMITTED");
        assertThat(body.get(1).get("action").asString()).isEqualTo("QUERY_EXECUTED");
    }

    @Test
    void non2xxResponseThrowsDeliveryException() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> deliverer.deliver(sink(), List.of(event("QUERY_SUBMITTED"))))
                .isInstanceOf(AuditSinkDeliveryException.class)
                .hasMessageContaining("HTTPS batch delivery failed");
    }

    private static String hmacHex(byte[] body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class ReversibleEncryption implements CredentialEncryptionService {
        @Override
        public String encrypt(String plaintext) {
            return "enc:" + plaintext;
        }

        @Override
        public String decrypt(String ciphertext) {
            return ciphertext.substring("enc:".length());
        }
    }
}
