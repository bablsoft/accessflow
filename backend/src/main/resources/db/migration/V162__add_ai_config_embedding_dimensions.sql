-- AF-918: an optional per-config embedding vector length. NULL means "the provider's default",
-- which is what every existing row gets and what every provider except Voyage has always used.
--
-- Two things need it. Voyage models produce 256 / 512 / 1024 (default) / 2048 dimensions and cannot
-- produce the 1536 that ACCESSFLOW_RAG_PGVECTOR_DIMENSIONS defaults to, so the value has to be
-- selectable and validated against the vector(N) column before ingest rather than after. OpenAI's
-- text-embedding-3-* models can also be shortened, which was impossible to express until now.
ALTER TABLE ai_config
    ADD COLUMN embedding_dimensions INTEGER;

COMMENT ON COLUMN ai_config.embedding_dimensions IS
    'Embedding vector length; NULL = provider default. Validated against the pgvector column width.';
