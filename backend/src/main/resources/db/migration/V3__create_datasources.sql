CREATE TYPE db_type  AS ENUM ('POSTGRESQL', 'MYSQL');
CREATE TYPE ssl_mode AS ENUM ('DISABLE', 'REQUIRE', 'VERIFY_CA', 'VERIFY_FULL');

CREATE TABLE datasources (
    id                    UUID         PRIMARY KEY,
    organization_id       UUID         NOT NULL REFERENCES organizations(id),
    name                  VARCHAR(255) NOT NULL,
    db_type               db_type      NOT NULL,
    host                  VARCHAR(255) NOT NULL,
    port                  INTEGER      NOT NULL,
    database_name         VARCHAR(255) NOT NULL,
    username              VARCHAR(255) NOT NULL,
    password_encrypted    TEXT         NOT NULL,
    ssl_mode              ssl_mode     NOT NULL DEFAULT 'DISABLE',
    connection_pool_size  INTEGER      NOT NULL DEFAULT 10,
    max_rows_per_query    INTEGER      NOT NULL DEFAULT 1000,
    require_review_reads  BOOLEAN      NOT NULL DEFAULT false,
    require_review_writes BOOLEAN      NOT NULL DEFAULT true,
    review_plan_id        UUID,                          -- FK added in V5 after review_plans exists
    ai_analysis_enabled   BOOLEAN      NOT NULL DEFAULT true,
    is_active             BOOLEAN      NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
