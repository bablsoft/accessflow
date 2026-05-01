CREATE TYPE ai_provider AS ENUM ('OPENAI', 'ANTHROPIC', 'OLLAMA');
CREATE TYPE risk_level  AS ENUM ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL');

CREATE TABLE ai_analyses (
    id                       UUID         PRIMARY KEY,
    query_request_id         UUID         NOT NULL REFERENCES query_requests(id),
    ai_provider              ai_provider  NOT NULL,
    ai_model                 VARCHAR(100) NOT NULL,
    risk_score               INTEGER      NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    risk_level               risk_level   NOT NULL,
    summary                  TEXT         NOT NULL,
    issues                   JSONB        NOT NULL DEFAULT '[]',
    missing_indexes_detected BOOLEAN      NOT NULL DEFAULT false,
    affects_row_estimate     BIGINT,
    prompt_tokens            INTEGER      NOT NULL DEFAULT 0,
    completion_tokens        INTEGER      NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Resolve the circular FK: query_requests.ai_analysis_id -> ai_analyses
ALTER TABLE query_requests
    ADD CONSTRAINT fk_query_requests_ai_analysis
    FOREIGN KEY (ai_analysis_id) REFERENCES ai_analyses(id);
