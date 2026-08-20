package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Generic HTTPS receiver sink: the batch as a JSON array, signed with the webhook-notification
 * signature contract — {@code X-AccessFlow-Signature: sha256=<hex HMAC-SHA256>} computed over
 * the exact body bytes sent.
 */
@Component
@Slf4j
class HttpsBatchSinkDeliverer implements AuditSinkDeliverer {

    static final String HEADER_SIGNATURE = "X-AccessFlow-Signature";
    static final String HEADER_EVENT = "X-AccessFlow-Event";
    static final String HEADER_DELIVERY = "X-AccessFlow-Delivery";
    static final String EVENT_NAME = "audit.batch";

    private final AuditSinkConfigCodec codec;
    private final AuditExportEventWriter eventWriter;
    private final RestClient restClient;

    HttpsBatchSinkDeliverer(AuditSinkConfigCodec codec,
                            AuditExportEventWriter eventWriter,
                            @Qualifier("auditSinkRestClient") RestClient restClient) {
        this.codec = codec;
        this.eventWriter = eventWriter;
        this.restClient = restClient;
    }

    @Override
    public AuditSinkType type() {
        return AuditSinkType.HTTPS_BATCH;
    }

    @Override
    public void deliver(AuditSinkEntity sink, List<AuditExportEvent> batch) {
        var config = codec.decodeHttpsBatch(sink.getConfigJson());
        var body = eventWriter.toJsonArray(batch).getBytes(StandardCharsets.UTF_8);
        var signature = "sha256=" + SinkHmacSigner.sha256Hex(body, config.secretPlain());
        try {
            restClient.post()
                    .uri(config.url())
                    .header(HEADER_SIGNATURE, signature)
                    .header(HEADER_EVENT, EVENT_NAME)
                    .header(HEADER_DELIVERY, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new AuditSinkDeliveryException("HTTPS batch delivery failed: " + ex.getMessage(), ex);
        }
    }
}
