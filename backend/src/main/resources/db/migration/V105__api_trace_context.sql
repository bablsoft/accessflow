-- AF-517: W3C trace context (https://www.w3.org/TR/trace-context/). Each governed API request carries
-- a trace id + span id (generated at submit when absent), propagated to the upstream as a `traceparent`
-- header on execution. The connector's `trace_header_mapping` lets an admin rename the header keys that
-- carry the context (some upstreams expect custom names instead of the standard `traceparent` /
-- `tracestate`). The trace / span ids are filterable on the API requests list.

ALTER TABLE api_requests
    ADD COLUMN trace_id TEXT,
    ADD COLUMN span_id  TEXT;

CREATE INDEX idx_api_requests_trace ON api_requests (organization_id, trace_id);
CREATE INDEX idx_api_requests_span ON api_requests (organization_id, span_id);

ALTER TABLE api_connectors
    ADD COLUMN trace_header_mapping JSONB NOT NULL
        DEFAULT '{"traceparent":"traceparent","tracestate":"tracestate"}';
