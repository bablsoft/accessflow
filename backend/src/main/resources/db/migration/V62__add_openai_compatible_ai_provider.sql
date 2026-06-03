-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V62__add_openai_compatible_ai_provider.sql.conf sets executeInTransaction=false so
-- Flyway runs this statement autocommit. OPENAI_COMPATIBLE reuses the OpenAI Spring AI client
-- against an admin-supplied base URL, enabling any OpenAI API-compatible backend (vLLM, LM Studio,
-- Together, Groq, OpenRouter, ...). One ALTER covers both ai_config.provider and
-- ai_analyses.ai_provider, which share the ai_provider enum type.
ALTER TYPE ai_provider ADD VALUE IF NOT EXISTS 'OPENAI_COMPATIBLE';
