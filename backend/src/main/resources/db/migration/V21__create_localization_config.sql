CREATE TABLE localization_config (
    id                   UUID         PRIMARY KEY,
    organization_id      UUID         NOT NULL UNIQUE REFERENCES organizations(id),
    available_languages  TEXT[]       NOT NULL CHECK (cardinality(available_languages) > 0),
    default_language     VARCHAR(20)  NOT NULL,
    ai_review_language   VARCHAR(20)  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT default_language_in_available_languages
        CHECK (default_language = ANY (available_languages))
);
