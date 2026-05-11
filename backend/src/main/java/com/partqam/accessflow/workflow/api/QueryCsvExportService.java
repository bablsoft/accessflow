package com.partqam.accessflow.workflow.api;

import com.partqam.accessflow.core.api.QueryListFilter;

/**
 * Builds a CSV export of queries matching the supplied {@link QueryListFilter}. The service owns
 * the export contract end-to-end: header layout, RFC 4180 escaping, the row cap, and the
 * timestamped filename. Controllers should only invoke this service and wrap the result in an
 * HTTP response — no formatting decisions belong above this layer.
 */
public interface QueryCsvExportService {

    CsvExport exportQueries(QueryListFilter filter);

    /**
     * @param body       UTF-8 encoded CSV bytes ready to write to the response body.
     * @param filename   Suggested {@code Content-Disposition} filename (UTC-stamped).
     * @param truncated  {@code true} when the filter matched more rows than the export cap; the
     *                   {@code body} stops at the cap and callers should surface a warning.
     */
    record CsvExport(byte[] body, String filename, boolean truncated) {
    }
}
