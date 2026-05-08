import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { QueryClient } from '@tanstack/react-query';

// Stub the WebSocket global before importing the manager so the singleton picks up our mock.
class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  static OPEN = 1;
  static CLOSED = 3;

  url: string;
  onopen: ((ev: unknown) => void) | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev: unknown) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;
  closed = false;
  closeArg: number | undefined;

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  close(code?: number) {
    this.closed = true;
    this.closeArg = code;
  }

  triggerOpen() {
    this.onopen?.({});
  }

  triggerMessage(payload: unknown) {
    const data = typeof payload === 'string' ? payload : JSON.stringify(payload);
    this.onmessage?.({ data });
  }

  triggerClose() {
    this.onclose?.({});
  }
}

(globalThis as unknown as { WebSocket: typeof FakeWebSocket }).WebSocket = FakeWebSocket;

vi.stubEnv('VITE_WS_URL', 'ws://test.local/ws');

const { websocketManager } = await import('../websocketManager');

function makeQueryClient(): { client: QueryClient; spy: ReturnType<typeof vi.fn> } {
  const spy = vi.fn();
  const client = { invalidateQueries: spy } as unknown as QueryClient;
  return { client, spy };
}

const sock = (i: number): FakeWebSocket => {
  const s = FakeWebSocket.instances[i];
  if (!s) throw new Error(`expected FakeWebSocket at index ${i}`);
  return s;
};

describe('websocketManager', () => {
  beforeEach(() => {
    FakeWebSocket.instances.length = 0;
    websocketManager.disconnect();
    vi.useFakeTimers();
  });

  afterEach(() => {
    websocketManager.disconnect();
    vi.useRealTimers();
  });

  it('opens a socket with the token in the query string on connect', () => {
    websocketManager.connect('abc.token');
    expect(FakeWebSocket.instances).toHaveLength(1);
    expect(sock(0).url).toBe('ws://test.local/ws?token=abc.token');
  });

  it('does not reopen the socket when called with the same token', () => {
    websocketManager.connect('same');
    websocketManager.connect('same');
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it('reopens the socket when called with a different token', () => {
    websocketManager.connect('first');
    websocketManager.connect('second');
    expect(FakeWebSocket.instances).toHaveLength(2);
    expect(sock(1).url).toBe('ws://test.local/ws?token=second');
  });

  it('schedules reconnects with exponential backoff capped at 30 s', () => {
    websocketManager.connect('t');
    const expected = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000];
    for (const delay of expected) {
      const sock = FakeWebSocket.instances.at(-1);
      expect(sock).toBeDefined();
      sock!.triggerClose();
      vi.advanceTimersByTime(delay - 1);
      // Not yet reconnected.
      expect(FakeWebSocket.instances.at(-1)).toBe(sock);
      vi.advanceTimersByTime(1);
      expect(FakeWebSocket.instances.length).toBeGreaterThan(0);
      // A new instance is created on each reconnect.
      expect(FakeWebSocket.instances.at(-1)).not.toBe(sock);
    }
  });

  it('resets backoff after a successful open', () => {
    websocketManager.connect('t');
    sock(0).triggerClose();
    vi.advanceTimersByTime(1_000);
    // Second instance opens, then succeeds.
    sock(1).triggerOpen();
    // Now drop again — backoff should restart at 1 s, not jump to 2 s.
    sock(1).triggerClose();
    vi.advanceTimersByTime(999);
    expect(FakeWebSocket.instances).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(FakeWebSocket.instances).toHaveLength(3);
  });

  it('disconnect cancels any pending reconnect', () => {
    websocketManager.connect('t');
    sock(0).triggerClose();
    websocketManager.disconnect();
    vi.advanceTimersByTime(60_000);
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it('subscribe and unsubscribe lifecycle', () => {
    websocketManager.connect('t');
    const handler = vi.fn();
    const off = websocketManager.subscribe('query.status_changed', handler);
    sock(0).triggerMessage({
      event: 'query.status_changed',
      timestamp: '2026-05-07T00:00:00Z',
      data: { query_id: 'q1', old_status: 'PENDING_AI', new_status: 'PENDING_REVIEW' },
    });
    expect(handler).toHaveBeenCalledOnce();
    expect(handler).toHaveBeenCalledWith({
      query_id: 'q1',
      old_status: 'PENDING_AI',
      new_status: 'PENDING_REVIEW',
    });

    off();
    sock(0).triggerMessage({
      event: 'query.status_changed',
      timestamp: '2026-05-07T00:00:01Z',
      data: { query_id: 'q1', old_status: 'PENDING_REVIEW', new_status: 'APPROVED' },
    });
    expect(handler).toHaveBeenCalledOnce();
  });

  it('default invalidations fire on receipt for query.status_changed', () => {
    const { client, spy } = makeQueryClient();
    websocketManager.bindQueryClient(client);
    websocketManager.connect('t');

    sock(0).triggerMessage({
      event: 'query.status_changed',
      timestamp: 'now',
      data: { query_id: 'q1', old_status: 'PENDING_AI', new_status: 'PENDING_REVIEW' },
    });

    expect(spy).toHaveBeenCalledWith({ queryKey: ['queries', 'detail', 'q1'] });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['queries', 'list'] });
  });

  it('default invalidation fires for notification.created', () => {
    const { client, spy } = makeQueryClient();
    websocketManager.bindQueryClient(client);
    websocketManager.connect('t');

    sock(0).triggerMessage({
      event: 'notification.created',
      timestamp: 'now',
      data: {
        notification_id: 'n1',
        event_type: 'QUERY_APPROVED',
        query_id: null,
        created_at: '2026-05-08T10:00:00Z',
      },
    });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['notifications', 'list'] });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['notifications', 'unread-count'] });
  });

  it('default invalidations fire for review.new_request and review.decision_made', () => {
    const { client, spy } = makeQueryClient();
    websocketManager.bindQueryClient(client);
    websocketManager.connect('t');

    sock(0).triggerMessage({
      event: 'review.new_request',
      timestamp: 'now',
      data: { query_id: 'q9', risk_level: 'HIGH', submitter: null, datasource: null },
    });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['reviews', 'pending'] });

    spy.mockReset();
    sock(0).triggerMessage({
      event: 'review.decision_made',
      timestamp: 'now',
      data: { query_id: 'q9', decision: 'APPROVED', reviewer: null, comment: null },
    });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['reviews', 'pending'] });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['queries', 'detail', 'q9'] });
  });

  it('drops non-JSON frames without throwing', () => {
    websocketManager.connect('t');
    expect(() => sock(0).triggerMessage('not json')).not.toThrow();
  });

  it('drops envelopes with unknown event names', () => {
    websocketManager.connect('t');
    const handler = vi.fn();
    websocketManager.subscribe('query.status_changed', handler);
    sock(0).triggerMessage({
      event: 'totally.unknown',
      timestamp: 'now',
      data: { query_id: 'q' },
    });
    expect(handler).not.toHaveBeenCalled();
  });

  it('continues delivering after a subscriber throws', () => {
    websocketManager.connect('t');
    const bad = vi.fn(() => {
      throw new Error('boom');
    });
    const good = vi.fn();
    websocketManager.subscribe('query.executed', bad);
    websocketManager.subscribe('query.executed', good);

    sock(0).triggerMessage({
      event: 'query.executed',
      timestamp: 'now',
      data: { query_id: 'q', rows_affected: 1, duration_ms: 50 },
    });

    expect(bad).toHaveBeenCalledOnce();
    expect(good).toHaveBeenCalledOnce();
  });
});
