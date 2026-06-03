-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V63__add_hugging_face_ai_provider.sql.conf sets executeInTransaction=false so
-- Flyway runs this statement autocommit. HUGGING_FACE reuses the OpenAI Spring AI client against
-- the Hugging Face Inference Providers router (https://router.huggingface.co/v1) by default, and
-- can also point at a local / self-hosted Text Generation Inference (TGI) server or a Dedicated
-- Inference Endpoint via an admin-supplied base URL — both speak the OpenAI-compatible
-- /v1/chat/completions wire format. It is keyless-capable so local TGI runs tokenless. One ALTER
-- covers both ai_config.provider and ai_analyses.ai_provider, which share the ai_provider enum type.
ALTER TYPE ai_provider ADD VALUE IF NOT EXISTS 'HUGGING_FACE';
