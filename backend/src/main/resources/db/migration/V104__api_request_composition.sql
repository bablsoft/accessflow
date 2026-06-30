-- AF-517: API request composition. Brings the governed-call request surface closer to a Postman-grade
-- composer: a body-type discriminator (raw / form-data / x-www-form-urlencoded / binary), query
-- parameters, multipart/binary form fields (file parts carried as bounded base64 inline — AccessFlow
-- has no object storage), and the upstream response Content-Type so the stored snapshot can be
-- downloaded in its correct format.

CREATE TYPE api_body_type AS ENUM ('NONE', 'RAW', 'FORM_DATA', 'FORM_URLENCODED', 'BINARY');

ALTER TABLE api_requests
    ADD COLUMN body_type             api_body_type NOT NULL DEFAULT 'RAW',
    ADD COLUMN request_content_type  TEXT,
    ADD COLUMN query_params          JSONB         NOT NULL DEFAULT '{}',
    ADD COLUMN form_fields           JSONB         NOT NULL DEFAULT '[]',
    ADD COLUMN binary_filename       TEXT,
    ADD COLUMN response_content_type TEXT;
