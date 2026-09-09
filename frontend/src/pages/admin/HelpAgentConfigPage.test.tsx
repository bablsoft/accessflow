import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AiConfig, HelpAgentConfig } from '@/types/api';

const {
  getHelpAgentConfigMock,
  updateHelpAgentConfigMock,
  testHelpAgentConfigMock,
  reindexHelpCorpusMock,
  listAiConfigsMock,
  getRagCapabilitiesMock,
} = vi.hoisted(() => ({
  getHelpAgentConfigMock: vi.fn(),
  updateHelpAgentConfigMock: vi.fn(),
  testHelpAgentConfigMock: vi.fn(),
  reindexHelpCorpusMock: vi.fn(),
  listAiConfigsMock: vi.fn(),
  getRagCapabilitiesMock: vi.fn(),
}));

vi.mock('@/api/helpAgent', async () => {
  const actual = await vi.importActual<typeof import('@/api/helpAgent')>('@/api/helpAgent');
  return {
    ...actual,
    getHelpAgentConfig: getHelpAgentConfigMock,
    updateHelpAgentConfig: updateHelpAgentConfigMock,
    testHelpAgentConfig: testHelpAgentConfigMock,
    reindexHelpCorpus: reindexHelpCorpusMock,
  };
});

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return {
    ...actual,
    listAiConfigs: listAiConfigsMock,
    getRagCapabilities: getRagCapabilitiesMock,
  };
});

const { HelpAgentConfigPage } = await import('./HelpAgentConfigPage');

function config(partial: Partial<HelpAgentConfig> = {}): HelpAgentConfig {
  return {
    id: 'cfg-1',
    organization_id: 'org-1',
    enabled: true,
    ai_config_id: 'ai-1',
    retrieval_enabled: true,
    top_k: 6,
    similarity_threshold: 0.4,
    max_history_turns: 8,
    max_question_chars: 2000,
    send_user_context: true,
    retention_days: 90,
    per_user_requests_per_minute: 6,
    indexed_corpus_version: 'c0ac599ef7fc',
    indexed_at: '2026-09-07T09:49:29Z',
    created_at: '2026-09-07T09:00:00Z',
    updated_at: '2026-09-07T09:49:29Z',
    ...partial,
  };
}

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
    embedding_dimensions: null,
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

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('HelpAgentConfigPage', () => {
  beforeEach(() => {
    getHelpAgentConfigMock.mockReset();
    updateHelpAgentConfigMock.mockReset();
    testHelpAgentConfigMock.mockReset();
    reindexHelpCorpusMock.mockReset();
    listAiConfigsMock.mockReset();
    getRagCapabilitiesMock.mockReset();
    getHelpAgentConfigMock.mockResolvedValue(config());
    listAiConfigsMock.mockResolvedValue([aiConfig()]);
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });
  });

  it('renders the corpus status from the server', async () => {
    render(wrap(<HelpAgentConfigPage />));
    expect(await screen.findByText('c0ac599ef7fc')).toBeInTheDocument();
    expect(screen.getByText('Ready')).toBeInTheDocument();
  });

  it('saves the form, sending the bound configuration id', async () => {
    updateHelpAgentConfigMock.mockResolvedValue(config());
    render(wrap(<HelpAgentConfigPage />));
    fireEvent.click(await screen.findByRole('button', { name: /Save changes/ }));

    await waitFor(() => expect(updateHelpAgentConfigMock).toHaveBeenCalled());
    expect(updateHelpAgentConfigMock.mock.calls[0]?.[0]).toMatchObject({
      enabled: true,
      ai_config_id: 'ai-1',
      retrieval_enabled: true,
      top_k: 6,
      retention_days: 90,
    });
    expect(await screen.findByText('Help assistant settings saved')).toBeInTheDocument();
  });

  it('unbinds with clear_ai_config rather than a null id', async () => {
    getHelpAgentConfigMock.mockResolvedValue(config({ enabled: false, ai_config_id: null }));
    updateHelpAgentConfigMock.mockResolvedValue(config({ enabled: false, ai_config_id: null }));
    render(wrap(<HelpAgentConfigPage />));
    fireEvent.click(await screen.findByRole('button', { name: /Save changes/ }));

    await waitFor(() => expect(updateHelpAgentConfigMock).toHaveBeenCalled());
    const body = updateHelpAgentConfigMock.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body.clear_ai_config).toBe(true);
    expect(body).not.toHaveProperty('ai_config_id');
  });

  it('surfaces the server detail when a save is rejected', async () => {
    updateHelpAgentConfigMock.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          error: 'HELP_AGENT_CONFIG_INVALID',
          detail: 'Top-K must be between 1 and 20',
        },
      },
      message: 'Request failed with status code 400',
    });
    render(wrap(<HelpAgentConfigPage />));
    fireEvent.click(await screen.findByRole('button', { name: /Save changes/ }));

    expect(await screen.findByText('Top-K must be between 1 and 20')).toBeInTheDocument();
  });

  it('reports a load failure instead of an empty form', async () => {
    getHelpAgentConfigMock.mockRejectedValue(new Error('boom'));
    render(wrap(<HelpAgentConfigPage />));
    expect(
      await screen.findByText('The help assistant settings could not be loaded'),
    ).toBeInTheDocument();
  });

  it('shows the last indexing failure the indexer recorded', async () => {
    getHelpAgentConfigMock.mockResolvedValue(
      config({
        index_error:
          "The embedding model produces 1024-dimension vectors but the pgvector column is 1536",
      }),
    );
    render(wrap(<HelpAgentConfigPage />));
    expect(
      await screen.findByText(/produces 1024-dimension vectors but the pgvector column is 1536/),
    ).toBeInTheDocument();
    expect(screen.getByText('Last indexing pass failed')).toBeInTheDocument();
  });

  it('requests a re-index', async () => {
    reindexHelpCorpusMock.mockResolvedValue(undefined);
    render(wrap(<HelpAgentConfigPage />));
    fireEvent.click(await screen.findByRole('button', { name: /Re-index documentation/ }));
    await waitFor(() => expect(reindexHelpCorpusMock).toHaveBeenCalled());
    expect(
      await screen.findByText(/Re-indexing started/),
    ).toBeInTheDocument();
  });

  it('reports the retrieval test result from the server detail', async () => {
    testHelpAgentConfigMock.mockResolvedValue({
      status: 'ERROR',
      detail: "The embedding model's vector dimension does not match the pgvector column dimension",
      embedding_dimensions: null,
    });
    render(wrap(<HelpAgentConfigPage />));
    fireEvent.click(await screen.findByRole('button', { name: /Test retrieval/ }));
    expect(
      await screen.findByText(/vector dimension does not match the pgvector column dimension/),
    ).toBeInTheDocument();
  });

  describe('retrieval-unavailable explanations', () => {
    const quickReference = /it answers from a built-in quick reference/;

    it('explains that no AI configuration is selected', async () => {
      getHelpAgentConfigMock.mockResolvedValue(config({ ai_config_id: null }));
      render(wrap(<HelpAgentConfigPage />));
      expect(
        await screen.findByText(/No AI configuration is selected/),
      ).toBeInTheDocument();
      expect(screen.getByText(quickReference)).toBeInTheDocument();
    });

    it('explains that the bound configuration has RAG switched off', async () => {
      listAiConfigsMock.mockResolvedValue([aiConfig({ rag_enabled: false })]);
      render(wrap(<HelpAgentConfigPage />));
      expect(
        await screen.findByText(/has no RAG knowledge base enabled/),
      ).toBeInTheDocument();
    });

    it('explains a missing embedding provider', async () => {
      listAiConfigsMock.mockResolvedValue([aiConfig({ embedding_provider: null })]);
      render(wrap(<HelpAgentConfigPage />));
      expect(await screen.findByText(/has no embedding provider/)).toBeInTheDocument();
    });

    it('explains that Anthropic cannot embed', async () => {
      listAiConfigsMock.mockResolvedValue([aiConfig({ embedding_provider: 'ANTHROPIC' })]);
      render(wrap(<HelpAgentConfigPage />));
      expect(
        await screen.findByText(/Anthropic offers no embeddings API/),
      ).toBeInTheDocument();
    });

    it('explains pgvector being unavailable — extension absent or migration skipped', async () => {
      getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: false });
      render(wrap(<HelpAgentConfigPage />));
      expect(
        await screen.findByText(
          /vector extension is not installed or ACCESSFLOW_RAG_PGVECTOR_ENABLED is false/,
        ),
      ).toBeInTheDocument();
    });

    it('shows no explanation when retrieval is ready', async () => {
      render(wrap(<HelpAgentConfigPage />));
      await screen.findByText('c0ac599ef7fc');
      expect(screen.queryByText(quickReference)).not.toBeInTheDocument();
    });
  });
});
