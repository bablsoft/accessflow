package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Splunk HTTP Event Collector sink: one POST per batch of newline-stacked HEC envelopes
 * ({@code {"time", "sourcetype", "source", "index"?, "event"}}), authenticated with
 * {@code Authorization: Splunk <token>}. HEC accepts multiple stacked envelopes per request.
 */
@Component
@Slf4j
class SplunkHecSinkDeliverer implements AuditSinkDeliverer {

    static final String SOURCETYPE = "accessflow:audit";

    private final AuditSinkConfigCodec codec;
    private final AuditExportEventWriter eventWriter;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    SplunkHecSinkDeliverer(AuditSinkConfigCodec codec,
                           AuditExportEventWriter eventWriter,
                           ObjectMapper objectMapper,
                           @Qualifier("auditSinkRestClient") RestClient restClient) {
        this.codec = codec;
        this.eventWriter = eventWriter;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public AuditSinkType type() {
        return AuditSinkType.SPLUNK_HEC;
    }

    @Override
    public void deliver(AuditSinkEntity sink, List<AuditExportEvent> batch) {
        var config = codec.decodeSplunkHec(sink.getConfigJson());
        var body = batch.stream()
                .map(event -> envelope(config.index(), config.source(), event))
                .collect(Collectors.joining("\n"))
                .getBytes(StandardCharsets.UTF_8);
        try {
            restClient.post()
                    .uri(config.url())
                    .header(HttpHeaders.AUTHORIZATION, "Splunk " + config.tokenPlain())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new AuditSinkDeliveryException("Splunk HEC delivery failed: " + ex.getMessage(), ex);
        }
    }

    private String envelope(String index, String source, AuditExportEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("time", hecTime(event));
        fields.put("sourcetype", SOURCETYPE);
        fields.put("source", source);
        if (index != null && !index.isBlank()) {
            fields.put("index", index);
        }
        fields.put("event", objectMapper.readTree(eventWriter.toJson(event)));
        return objectMapper.writeValueAsString(fields);
    }

    /** Epoch seconds with microsecond fraction, the HEC {@code time} convention. */
    private static BigDecimal hecTime(AuditExportEvent event) {
        var createdAt = event.createdAt();
        return BigDecimal.valueOf(createdAt.getEpochSecond())
                .add(BigDecimal.valueOf(createdAt.getNano(), 9))
                .setScale(6, RoundingMode.DOWN);
    }
}
