package com.bablsoft.accessflow.compliance.events;

import com.bablsoft.accessflow.core.api.DataClassification;

import java.util.List;
import java.util.UUID;

/**
 * A query result containing classified columns left AccessFlow (#626) — via the export endpoint
 * ({@code trigger = "endpoint"}) or as a results-CSV email attachment
 * ({@code trigger = "email_attachment"}). Consumed by the notifications module to raise the
 * advisory {@code SENSITIVE_RESULT_EXPORTED} fanout to organization admins. Published outside
 * any transaction — consume with a plain {@code @EventListener}, not
 * {@code @ApplicationModuleListener}.
 */
public record SensitiveResultExportedEvent(
        UUID organizationId,
        UUID queryRequestId,
        UUID datasourceId,
        UUID exporterUserId,
        String exporterEmail,
        String format,
        long rowCount,
        boolean watermarked,
        List<DataClassification> classifications,
        String trigger) {

    public SensitiveResultExportedEvent {
        classifications = classifications == null ? List.of() : List.copyOf(classifications);
    }
}
