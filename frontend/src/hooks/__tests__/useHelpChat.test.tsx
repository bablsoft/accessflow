import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { usePreferencesStore } from '@/store/preferencesStore';
import type { HelpChatConversation, HelpChatTurn } from '@/types/api';

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

const { useHelpChat } = await import('../useHelpChat');

const SESSION = { id: 's1', title: '', message_count: 0, created_at: '2026-09-08T10:00:00Z' };

function conversation(): HelpChatConversation {
  return { session: SESSION, messages: [] };
}

function turn(): HelpChatTurn {
  return {
    session: { ...SESSION, message_count: 2, title: 'How do I submit a query?' },
    user_message: {
      id: 'm1',
      role: 'USER',
      content: 'How do I submit a query?',
      citations: [],
      created_at: '2026-09-08T10:01:00Z',
    },
    assistant_message: {
      id: 'm2',
      role: 'ASSISTANT',
      content: 'Open the query editor. [1]',
      citations: [],
      created_at: '2026-09-08T10:01:02Z',
    },
  };
}

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('useHelpChat', () => {
  beforeEach(() => {
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
    usePreferencesStore.setState({ activeHelpSessionId: null, helpChatOpen: false });
  });

  it('reads availability and leaves the conversation query idle with no session', async () => {
    const { result } = renderHook(() => useHelpChat(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.availability?.enabled).toBe(true));
    expect(fetchHelpChatSessionMock).not.toHaveBeenCalled();
    expect(result.current.hasConversation).toBe(false);
  });

  it('opens a session on the first question and remembers its id', async () => {
    createHelpChatSessionMock.mockResolvedValue(SESSION);
    askHelpChatMock.mockResolvedValue(turn());
    fetchHelpChatSessionMock.mockResolvedValue(conversation());

    const { result } = renderHook(() => useHelpChat(), { wrapper: wrapper() });
    await act(async () => {
      await result.current.askQuestion('How do I submit a query?', 'Query editor');
    });

    expect(createHelpChatSessionMock).toHaveBeenCalledTimes(1);
    expect(askHelpChatMock).toHaveBeenCalledWith('s1', {
      question: 'How do I submit a query?',
      route_name: 'Query editor',
    });
    expect(usePreferencesStore.getState().activeHelpSessionId).toBe('s1');
  });

  it('omits route_name entirely for an unmapped route', async () => {
    createHelpChatSessionMock.mockResolvedValue(SESSION);
    askHelpChatMock.mockResolvedValue(turn());
    fetchHelpChatSessionMock.mockResolvedValue(conversation());

    const { result } = renderHook(() => useHelpChat(), { wrapper: wrapper() });
    await act(async () => {
      await result.current.askQuestion('What is break-glass?');
    });

    expect(askHelpChatMock).toHaveBeenCalledWith('s1', { question: 'What is break-glass?' });
  });

  it('reuses an existing session rather than opening another', async () => {
    usePreferencesStore.setState({ activeHelpSessionId: 's1' });
    fetchHelpChatSessionMock.mockResolvedValue(conversation());
    askHelpChatMock.mockResolvedValue(turn());

    const { result } = renderHook(() => useHelpChat(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.conversation).toBeDefined());
    await act(async () => {
      await result.current.askQuestion('Another question');
    });

    expect(createHelpChatSessionMock).not.toHaveBeenCalled();
  });

  it('shows the answer from the returned turn without waiting for the refetch', async () => {
    usePreferencesStore.setState({ activeHelpSessionId: 's1' });
    fetchHelpChatSessionMock.mockResolvedValue(conversation());
    askHelpChatMock.mockResolvedValue(turn());

    const { result } = renderHook(() => useHelpChat(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.conversation).toBeDefined());
    await act(async () => {
      await result.current.askQuestion('How do I submit a query?');
    });
    // Both stored messages are in the transcript, and the optimistic placeholder is gone —
    // the ids are the server's, not `optimistic-…`.
    await waitFor(() => expect(result.current.messages.map((m) => m.id)).toEqual(['m1', 'm2']));
    expect(result.current.messages[1]?.content).toBe('Open the query editor. [1]');
    // And exactly one GET: the turn replaced the conversation, so nothing re-read it.
    expect(fetchHelpChatSessionMock).toHaveBeenCalledTimes(1);
  });

  it('appends the question optimistically and rolls it back when the ask fails', async () => {
    usePreferencesStore.setState({ activeHelpSessionId: 's1' });
    fetchHelpChatSessionMock.mockResolvedValue(conversation());
    let rejectAsk: (reason: unknown) => void = () => {};
    askHelpChatMock.mockReturnValue(
      new Promise((_resolve, reject) => {
        rejectAsk = reject;
      }),
    );

    const { result } = renderHook(() => useHelpChat(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.conversation).toBeDefined());

    let pending: Promise<unknown> = Promise.resolve();
    act(() => {
      pending = result.current.askQuestion('Optimistic question').catch(() => undefined);
    });
    await waitFor(() => expect(result.current.messages).toHaveLength(1));
    expect(result.current.messages[0]?.content).toBe('Optimistic question');

    await act(async () => {
      rejectAsk(new Error('provider down'));
      await pending;
    });
    await waitFor(() => expect(result.current.messages).toHaveLength(0));
  });

  it('forgets the conversation without deleting it', async () => {
    usePreferencesStore.setState({ activeHelpSessionId: 's1' });
    fetchHelpChatSessionMock.mockResolvedValue(conversation());
    const { result } = renderHook(() => useHelpChat(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.hasConversation).toBe(true));

    act(() => result.current.startNewConversation());
    expect(usePreferencesStore.getState().activeHelpSessionId).toBeNull();
    expect(result.current.hasConversation).toBe(false);
  });
});
