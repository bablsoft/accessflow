import { describe, expect, it } from 'vitest';
import { retrievalReadiness } from './helpAgentRetrieval';
import type { AiConfig } from '@/types/api';

function aiConfig(partial: Partial<AiConfig> = {}): AiConfig {
  return {
    id: 'ai-1',
    organization_id: 'org-1',
    name: 'Primary',
    provider: 'OPENAI',
    model: 'gpt-4o',
    endpoint: null,
    api_key: null,
    timeout_ms: 30_000,
    max_prompt_tokens: 8000,
    max_completion_tokens: 2000,
    system_prompt_template: null,
    langfuse_prompt_name: null,
    langfuse_prompt_label: null,
    rag_enabled: true,
    rag_store_type: 'PGVECTOR',
    rag_top_k: 4,
    rag_similarity_threshold: 0.4,
    rag_endpoint: null,
    rag_collection: null,
    rag_api_key: null,
    embedding_provider: 'OPENAI',
    embedding_model: 'text-embedding-3-small',
    embedding_endpoint: null,
    embedding_api_key: null,
    orchestration_enabled: false,
    voting_strategy: 'MAJORITY',
    voting_weight: 1,
    guardrail_patterns: [],
    models: [],
    in_use_count: 0,
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    ...partial,
  };
}

describe('retrievalReadiness', () => {
  it('is ready for a pgvector config on a deployment that has pgvector', () => {
    expect(retrievalReadiness(aiConfig(), true)).toBe('ready');
  });

  it('is ready for a Qdrant config regardless of pgvector', () => {
    expect(retrievalReadiness(aiConfig({ rag_store_type: 'QDRANT' }), false)).toBe('ready');
  });

  it('reports no selected configuration', () => {
    expect(retrievalReadiness(undefined, true)).toBe('no_ai_config');
  });

  it('reports RAG switched off, and a missing store type as the same thing', () => {
    expect(retrievalReadiness(aiConfig({ rag_enabled: false }), true)).toBe('rag_disabled');
    expect(retrievalReadiness(aiConfig({ rag_store_type: null }), true)).toBe('rag_disabled');
  });

  it('reports a missing embedding provider', () => {
    expect(retrievalReadiness(aiConfig({ embedding_provider: null }), true)).toBe(
      'embedding_provider_missing',
    );
  });

  it('reports that Anthropic cannot embed', () => {
    expect(retrievalReadiness(aiConfig({ embedding_provider: 'ANTHROPIC' }), true)).toBe(
      'embedding_provider_anthropic',
    );
  });

  it('reports pgvector unavailable — extension absent or migration skipped alike', () => {
    expect(retrievalReadiness(aiConfig(), false)).toBe('pgvector_unavailable');
  });
});
