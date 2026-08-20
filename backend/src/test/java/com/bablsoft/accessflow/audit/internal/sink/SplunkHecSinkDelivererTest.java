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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SplunkHecSinkDelivererTest {

    private static final String HEC_URL = "https://splunk.example.com:8088/services/collector";
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T10:15:30.123456Z");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AuditSinkConfigCodec codec =
            new AuditSinkConfigCodec(mapper, new ReversibleEncryption());
    private final AuditExportEventWriter eventWriter = new AuditExportEventWriter(mapper);

    private MockRestServiceServer server;
    private SplunkHecSinkDeliverer deliverer;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        deliverer = new SplunkHecSinkDeliverer(codec, eventWriter, mapper, builder.build());
    }

    private AuditSinkEntity sink(Map<String, Object> config) {
        var sink = new AuditSinkEntity();
        sink.setId(UUID.randomUUID());
        sink.setOrganizationId(UUID.randomUUID());
        sink.setName("splunk");
        sink.setType(AuditSinkType.SPLUNK_HEC);
        sink.setConfigJson(codec.encodeForPersistence(AuditSinkType.SPLUNK_HEC, config));
        return sink;
    }

    private AuditExportEvent event(String action) {
        return new AuditExportEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                action, "query_request", UUID.randomUUID(), "{\"k\":1}", "203.0.113.5", "ua/1",
                CREATED_AT, "aa", "bb");
    }

    @Test
    void typeIsSplunkHec() {
        assertThat(deliverer.type()).isEqualTo(AuditSinkType.SPLUNK_HEC);
    }

    @Test
    void postsNewlineStackedEnvelopesWithSplunkAuth() {
        var bodyHolder = new StringBuilder();
        server.expect(requestTo(HEC_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Splunk hec-token"))
                .andExpect(request -> bodyHolder.append(
                        ((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess());

        deliverer.deliver(
                sink(Map.of("url", HEC_URL, "token", "hec-token", "index", "audit-idx")),
                List.of(event("QUERY_SUBMITTED"), event("QUERY_EXECUTED")));

        server.verify();
        var lines = bodyHolder.toString().split("\n");
        assertThat(lines).hasSize(2);
        var expectedTime = BigDecimal.valueOf(CREATED_AT.getEpochSecond())
                .add(BigDecimal.valueOf(123456, 6)).setScale(6);
        var first = mapper.readTree(lines[0]);
        assertThat(first.get("time").decimalValue()).isEqualByComparingTo(expectedTime);
        assertThat(first.get("sourcetype").asString()).isEqualTo("accessflow:audit");
        assertThat(first.get("source").asString()).isEqualTo("accessflow");
        assertThat(first.get("index").asString()).isEqualTo("audit-idx");
        assertThat(first.get("event").isObject()).isTrue();
        assertThat(first.get("event").get("action").asString()).isEqualTo("QUERY_SUBMITTED");
        var second = mapper.readTree(lines[1]);
        assertThat(second.get("event").get("action").asString()).isEqualTo("QUERY_EXECUTED");
    }

    @Test
    void omitsIndexWhenNotConfigured() {
        var bodyHolder = new StringBuilder();
        server.expect(requestTo(HEC_URL))
                .andExpect(request -> bodyHolder.append(
                        ((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess());

        deliverer.deliver(sink(Map.of("url", HEC_URL, "token", "hec-token")),
                List.of(event("QUERY_SUBMITTED")));

        server.verify();
        var envelope = mapper.readTree(bodyHolder.toString());
        assertThat(envelope.has("index")).isFalse();
    }

    @Test
    void non2xxResponseThrowsDeliveryException() {
        server.expect(requestTo(HEC_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> deliverer.deliver(
                sink(Map.of("url", HEC_URL, "token", "hec-token")),
                List.of(event("QUERY_SUBMITTED"))))
                .isInstanceOf(AuditSinkDeliveryException.class)
                .hasMessageContaining("Splunk HEC delivery failed");
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
