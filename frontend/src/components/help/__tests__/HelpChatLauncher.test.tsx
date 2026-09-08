import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { HelpAgentAvailability } from '@/types/api';

const { fetchHelpChatAvailabilityMock } = vi.hoisted(() => ({
  fetchHelpChatAvailabilityMock: vi.fn(),
}));

vi.mock('@/api/helpChat', async () => {
  const actual = await vi.importActual<typeof import('@/api/helpChat')>('@/api/helpChat');
  return { ...actual, fetchHelpChatAvailability: fetchHelpChatAvailabilityMock };
});

const { HelpChatLauncher } = await import('../HelpChatLauncher');

function availability(partial: Partial<HelpAgentAvailability> = {}): HelpAgentAvailability {
  return {
    enabled: true,
    retrieval_active: true,
    corpus_version: 'c0ac599ef7fc',
    chunk_count: 512,
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

describe('HelpChatLauncher', () => {
  beforeEach(() => {
    fetchHelpChatAvailabilityMock.mockReset();
  });

  it('renders nothing when the agent is disabled', async () => {
    fetchHelpChatAvailabilityMock.mockResolvedValue(availability({ enabled: false }));
    const { container } = render(wrap(<HelpChatLauncher />));
    await waitFor(() => expect(fetchHelpChatAvailabilityMock).toHaveBeenCalled());
    expect(container.querySelector('.ant-app')).toBeEmptyDOMElement();
    expect(screen.queryByRole('button', { name: /help assistant/i })).not.toBeInTheDocument();
  });

  it('renders nothing while availability is still unknown', () => {
    fetchHelpChatAvailabilityMock.mockReturnValue(new Promise(() => {}));
    const { container } = render(wrap(<HelpChatLauncher />));
    expect(container.querySelector('.ant-app')).toBeEmptyDOMElement();
  });

  it('renders the launcher when the agent is enabled', async () => {
    fetchHelpChatAvailabilityMock.mockResolvedValue(availability());
    render(wrap(<HelpChatLauncher />));
    expect(
      await screen.findByRole('button', { name: /Ask the help assistant/i }),
    ).toBeInTheDocument();
  });
});
