import type { AiConfig } from '@/types/api';

/**
 * Why documentation retrieval cannot be switched on, as far as the browser can tell (AF-906).
 *
 * The three pgvector states the backend distinguishes — extension missing, migration skipped,
 * dimension mismatch — are deployment facts the admin API reports as localized `detail` text on
 * save and on test; nothing in the payloads this page reads separates them, so they collapse to
 * `pgvector_unavailable` here and the server's own sentence is shown alongside. What *is* knowable
 * up front is knowable instantly, which is the point: an admin should not have to press Save to be
 * told the configuration they picked has no embedding provider.
 */
export type RetrievalReadiness =
  | 'ready'
  | 'no_ai_config'
  | 'rag_disabled'
  | 'embedding_provider_missing'
  | 'embedding_provider_anthropic'
  | 'pgvector_unavailable';

/**
 * @param aiConfig         the bound configuration, or `undefined` when nothing is selected
 * @param pgvectorAvailable whether the in-app pgvector store is usable on this deployment
 */
export function retrievalReadiness(
  aiConfig: AiConfig | undefined,
  pgvectorAvailable: boolean,
): RetrievalReadiness {
  if (aiConfig === undefined) return 'no_ai_config';
  if (!aiConfig.rag_enabled || aiConfig.rag_store_type === null) return 'rag_disabled';
  if (aiConfig.embedding_provider === null) return 'embedding_provider_missing';
  if (aiConfig.embedding_provider === 'ANTHROPIC') return 'embedding_provider_anthropic';
  if (aiConfig.rag_store_type === 'PGVECTOR' && !pgvectorAvailable) return 'pgvector_unavailable';
  return 'ready';
}
