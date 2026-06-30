-- AF-521: store the full API response for download. Raise the per-connector response cap default
-- from 1 MiB to 10 MiB (the system-wide hard ceiling, ACCESSFLOW_APIGOV_MAX_RESPONSE_BYTES) so large
-- responses (e.g. GET https://api.weather.gov/alerts) are stored in full and downloadable; the
-- detail view only embeds a bounded preview. Bump existing connectors still on the old 1 MiB default.
ALTER TABLE api_connectors ALTER COLUMN max_response_bytes SET DEFAULT 10485760;

UPDATE api_connectors SET max_response_bytes = 10485760 WHERE max_response_bytes = 1048576;
