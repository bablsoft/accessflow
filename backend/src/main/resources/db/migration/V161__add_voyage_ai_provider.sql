-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL.
-- The matching V161__add_voyage_ai_provider.sql.conf sets executeInTransaction=false so Flyway runs
-- this statement autocommit. VOYAGE (AF-918) is the first embedding-only member of ai_provider:
-- Voyage AI publishes an embeddings API but no chat completions, so it is accepted as
-- ai_config.embedding_provider and rejected as ai_config.provider / an orchestration member by
-- DefaultAiConfigService. No CHECK constraint is added here — PostgreSQL refuses to use a newly
-- added enum value in the transaction that created it, so any such constraint belongs in a later
-- migration. One ALTER covers ai_config.provider, ai_config.embedding_provider and
-- ai_analyses.ai_provider, which share the ai_provider enum type.
ALTER TYPE ai_provider ADD VALUE IF NOT EXISTS 'VOYAGE';
