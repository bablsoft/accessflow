import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { usePreferencesStore } from '@/store/preferencesStore';

const {
  fetchHelpChatAvailabilityMock,
  fetchHelpChatSessionMock,
  createHelpChatSessionMock,
  askHelpChatMock,
} = vi.hoisted(() => ({
  fetchHelpChatAvailabilityMock: vi.fn(),
  fetchHelpChatSessionMock: vi.fn(),
  createHelpChatSessionMock: vi.fn(),
  askHelpChatMock: vi.fn(),
}));

vi.mock('@/api/helpChat', async () => {
  const actual = await vi.importActual<typeof import('@/api/helpChat')>('@/api/helpChat');
  return {
    ...actual,
    fetchHelpChatAvailability: fetchHelpChatAvailabilityMock,
    fetchHelpChatSession: fetchHelpChatSessionMock,
    createHelpChatSession: createHelpChatSessionMock,
    askHelpChat: askHelpChatMock,
  };
});

const { HelpChatPanel } = await import('../HelpChatPanel');

const SESSION = { id: 's1', title: '', message_count: 0, created_at: '2026-09-08T10:00:00Z' };

function wrap(node: ReactNode, route: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('HelpChatPanel', () => {
  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn();
    fetchHelpChatAvailabilityMock.mockReset();
    fetchHelpChatSessionMock.mockReset();
    createHelpChatSessionMock.mockReset();
    askHelpChatMock.mockReset();
    fetchHelpChatAvailabilityMock.mockResolvedValue({
      enabled: true,
      retrieval_active: true,
      corpus_version: 'c0ac599ef7fc',
      chunk_count: 512,
    });
    createHelpChatSessionMock.mockResolvedValue(SESSION);
    fetchHelpChatSessionMock.mockResolvedValue({ session: SESSION, messages: [] });
    askHelpChatMock.mockResolvedValue({
      session: { ...SESSION, message_count: 2 },
      user_message: {
        id: 'm1',
        role: 'USER',
        content: 'q',
        citations: [],
        created_at: '2026-09-08T10:01:00Z',
      },
      assistant_message: {
        id: 'm2',
        role: 'ASSISTANT',
        content: 'a',
        citations: [],
        created_at: '2026-09-08T10:01:02Z',
      },
    });
    usePreferencesStore.setState({ activeHelpSessionId: null, helpChatOpen: true });
  });

  it('sends a route label, never the pathname, from a detail route carrying a UUID', async () => {
    const uuid = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';
    render(wrap(<HelpChatPanel open onClose={() => {}} />, `/queries/${uuid}`));

    const box = await screen.findByLabelText('Your question');
    fireEvent.change(box, { target: { value: 'How do I submit a query?' } });
    const send = screen.getByRole('button', { name: /Send/ });
    await waitFor(() => expect(send).toBeEnabled());
    fireEvent.click(send);

    await waitFor(() => expect(askHelpChatMock).toHaveBeenCalled());
    const body = askHelpChatMock.mock.calls[0]?.[1] as { route_name?: string };
    expect(body.route_name).toBe('Query history');
    expect(body.route_name).not.toContain(uuid);
    expect(body.route_name).not.toContain('/');
  });

  it('sends no route label at all for a route the panel does not name', async () => {
    render(wrap(<HelpChatPanel open onClose={() => {}} />, '/somewhere-new'));

    const box = await screen.findByLabelText('Your question');
    fireEvent.change(box, { target: { value: 'What is break-glass?' } });
    const send = screen.getByRole('button', { name: /Send/ });
    await waitFor(() => expect(send).toBeEnabled());
    fireEvent.click(send);

    await waitFor(() => expect(askHelpChatMock).toHaveBeenCalled());
    expect(askHelpChatMock.mock.calls[0]?.[1]).toEqual({ question: 'What is break-glass?' });
  });

  it('explains quick-reference mode when retrieval is inactive', async () => {
    fetchHelpChatAvailabilityMock.mockResolvedValue({
      enabled: true,
      retrieval_active: false,
      corpus_version: '',
      chunk_count: 0,
    });
    render(wrap(<HelpChatPanel open onClose={() => {}} />, '/editor'));
    expect(await screen.findByText('Quick-reference mode')).toBeInTheDocument();
  });

  it('surfaces the server detail when the question cannot be answered', async () => {
    askHelpChatMock.mockRejectedValue({
      isAxiosError: true,
      response: { data: { detail: 'You have asked too many questions this minute' } },
      message: 'Request failed',
    });
    render(wrap(<HelpChatPanel open onClose={() => {}} />, '/editor'));

    const box = await screen.findByLabelText('Your question');
    fireEvent.change(box, { target: { value: 'again?' } });
    const send = screen.getByRole('button', { name: /Send/ });
    await waitFor(() => expect(send).toBeEnabled());
    fireEvent.click(send);

    expect(
      await screen.findByText('You have asked too many questions this minute'),
    ).toBeInTheDocument();
  });
});
