import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AiConfig } from '@/types/api';

const { listAiConfigsMock, getAiConfigMock, updateAiConfigMock, listKnowledgeDocumentsMock } =
  vi.hoisted(() => ({
    listAiConfigsMock: vi.fn(),
    getAiConfigMock: vi.fn(),
    updateAiConfigMock: vi.fn(),
    listKnowledgeDocumentsMock: vi.fn(),
  }));

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return {
    ...actual,
    listAiConfigs: listAiConfigsMock,
    getAiConfig: getAiConfigMock,
    updateAiConfig: updateAiConfigMock,
    listKnowledgeDocuments: listKnowledgeDocumentsMock,
  };
});

const { AiConfigListPage } = await import('./AiConfigListPage');
const AiConfigEditPage = (await import('./AiConfigEditPage')).default;

function config(overrides: Partial<AiConfig> = {}): AiConfig {
  return {
    id: '5f0a4d3e-0000-4000-8000-000000000001',
    organization_id: '5f0a4d3e-0000-4000-8000-00000000aaaa',
    name: 'Claude Prod',
    provider: 'ANTHROPIC',
    model: 'claude-sonnet-4-20250514',
    endpoint: null,
    api_key: '********',
    timeout_ms: 30000,
    max_prompt_tokens: 8000,
    max_completion_tokens: 2000,
    system_prompt_template: null,
    langfuse_prompt_name: null,
    langfuse_prompt_label: null,
    rag_enabled: false,
    rag_store_type: null,
    rag_top_k: 4,
    rag_similarity_threshold: 0.5,
    rag_endpoint: null,
    rag_collection: null,
    rag_api_key: null,
    embedding_provider: null,
    embedding_model: null,
    embedding_endpoint: null,
    embedding_api_key: null,
    orchestration_enabled: false,
    voting_strategy: 'WEIGHTED_AVERAGE',
    voting_weight: 1,
    guardrail_patterns: [],
    models: [],
    fallback_priority: null,
    in_use_count: 0,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{node}</App>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  listKnowledgeDocumentsMock.mockResolvedValue([]);
});

describe('AiConfigListPage fallback tag', () => {
  it('shows the fallback tag with its priority for fallback configs only', async () => {
    listAiConfigsMock.mockResolvedValue([
      config({ id: '5f0a4d3e-0000-4000-8000-000000000001', name: 'Claude Prod' }),
      config({
        id: '5f0a4d3e-0000-4000-8000-000000000002',
        name: 'Local Ollama',
        provider: 'OLLAMA',
        fallback_priority: 0,
      }),
    ]);

    render(wrap(<MemoryRouter><AiConfigListPage /></MemoryRouter>));

    await waitFor(() => expect(screen.getByText('Local Ollama')).toBeInTheDocument());
    expect(screen.getByText('Fallback #0')).toBeInTheDocument();
    expect(screen.getAllByText(/Fallback #/)).toHaveLength(1);
  });
});

describe('AiConfigEditPage fallback priority', () => {
  function renderEditPage(cfg: AiConfig) {
    getAiConfigMock.mockResolvedValue(cfg);
    updateAiConfigMock.mockResolvedValue(cfg);
    render(
      wrap(
        <MemoryRouter initialEntries={[`/admin/ai-configs/${cfg.id}`]}>
          <Routes>
            <Route path="/admin/ai-configs/:id" element={<AiConfigEditPage />} />
          </Routes>
        </MemoryRouter>,
      ),
    );
  }

  it('prefills the stored priority and submits it unchanged', async () => {
    renderEditPage(config({ fallback_priority: 3 }));

    const field = await screen.findByLabelText('Fallback priority');
    expect(field).toHaveValue('3');

    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(updateAiConfigMock).toHaveBeenCalled());
    expect(updateAiConfigMock.mock.calls[0]?.[1]).toMatchObject({ fallback_priority: 3 });
  });

  it('sends -1 to clear the priority when the field is emptied', async () => {
    renderEditPage(config({ fallback_priority: 3 }));

    const field = await screen.findByLabelText('Fallback priority');
    fireEvent.change(field, { target: { value: '' } });

    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(updateAiConfigMock).toHaveBeenCalled());
    expect(updateAiConfigMock.mock.calls[0]?.[1]).toMatchObject({ fallback_priority: -1 });
  });
});
