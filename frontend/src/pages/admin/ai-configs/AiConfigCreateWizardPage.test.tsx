import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import AiConfigCreateWizardPage from './AiConfigCreateWizardPage';

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return { ...actual, getDefaultAiPrompt: vi.fn().mockResolvedValue({ template: '{{sql}}' }) };
});

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

describe('AiConfigCreateWizardPage provider step', () => {
  it('offers every chat-capable provider as a tile', () => {
    render(wrap(<AiConfigCreateWizardPage />));

    expect(screen.getByText('OpenAI')).toBeInTheDocument();
    expect(screen.getByText('Anthropic')).toBeInTheDocument();
    expect(screen.getByText('Ollama')).toBeInTheDocument();
    expect(screen.getByText('Custom (OpenAI-compatible)')).toBeInTheDocument();
    expect(screen.getByText('Hugging Face')).toBeInTheDocument();
  });

  // AF-918: Voyage publishes embeddings and no chat completions. The backend returns
  // AI_CONFIG_PROVIDER_INVALID for it, so offering a tile here would only lead to a 400.
  it('does not offer Voyage as a chat provider', () => {
    render(wrap(<AiConfigCreateWizardPage />));

    // Scoped to the tiles: a Voyage mention elsewhere in the wizard copy is not a failure.
    expect(screen.queryByRole('button', { name: /Voyage/i })).not.toBeInTheDocument();
  });
});
