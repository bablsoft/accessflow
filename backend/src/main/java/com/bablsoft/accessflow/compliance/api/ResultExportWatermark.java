package com.bablsoft.accessflow.compliance.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The deterministic watermark stamped onto governed result exports (#626): who exported, when
 * (UTC, second precision), and which query request — traceable back to the immutable query
 * snapshot. Single source of truth for the template: the CSV/PDF writers, the email-attachment
 * renderer, and the frontend preview ({@code frontend/src/utils/watermarkPreview.ts}) must all
 * produce exactly these strings. ASCII-safe by construction so the PDF writer's sanitizer never
 * mangles it.
 */
public final class ResultExportWatermark {

    private ResultExportWatermark() {
    }

    /** {@code AccessFlow export | <email> | <utc-iso-8601> | query <queryRequestId>} */
    public static String header(String exporterEmail, Instant timestamp, UUID queryRequestId) {
        return "AccessFlow export | " + exporterEmail + " | "
                + timestamp.truncatedTo(ChronoUnit.SECONDS) + " | query " + queryRequestId;
    }

    /** {@code AccessFlow export end | <n> rows} plus {@code  | capped at <cap>} when capped. */
    public static String footer(long rowCount, Integer rowCap) {
        var footer = "AccessFlow export end | " + rowCount + " rows";
        return rowCap == null ? footer : footer + " | capped at " + rowCap;
    }
}
