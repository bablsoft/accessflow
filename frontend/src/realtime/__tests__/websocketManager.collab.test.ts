import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { QueryClient } from '@tanstack/react-query';

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  static OPEN = 1;
  static CLOSED = 3;

  url: string;
  readyState = 0;
  sent: string[] = [];
  onopen: ((ev: unknown) => void) | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev: unknown) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED;
  }

  triggerOpen() {
    this.readyState = FakeWebSocket.OPEN;
    this.onopen?.({});
  }

  triggerMessage(payload: unknown) {
    const data = typeof payload === 'string' ? payload : JSON.stringify(payload);
    this.onmessage?.({ data });
  }
}

(globalThis as unknown as { WebSocket: typeof FakeWebSocket }).WebSocket = FakeWebSocket;
vi.stubEnv('VITE_WS_URL', 'ws://test.local/ws');

const { websocketManager } = await import('../websocketManager');

const sock = (i: number): FakeWebSocket => {
  const s = FakeWebSocket.instances[i];
  if (!s) throw new Error(`expected FakeWebSocket at index ${i}`);
  return s;
};

const sentFrames = (s: FakeWebSocket) => s.sent.map((raw) => JSON.parse(raw));

describe('websocketManager collaboration', () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
  });

  afterEach(() => {
    websocketManager.disconnect();
  });

  it('queues a join while connecting and replays it on open', () => {
    websocketManager.connect('tok');
    const ok = websocketManager.send({ type: 'collab.join', query_id: 'q-1' });
    expect(ok).toBe(false); // socket not open yet
    expect(sock(0).sent).toHaveLength(0);

    sock(0).triggerOpen();

    expect(sentFrames(sock(0))).toContainEqual({ type: 'collab.join', query_id: 'q-1' });
  });

  it('sends collaboration frames once the socket is open', () => {
    websocketManager.connect('tok');
    sock(0).triggerOpen();

    const ok = websocketManager.send({ type: 'collab.sync', query_id: 'q-1', update: 'AQID' });

    expect(ok).toBe(true);
    expect(sentFrames(sock(0))).toContainEqual({
      type: 'collab.sync',
      query_id: 'q-1',
      update: 'AQID',
    });
  });

  it('invalidates the comment query on a collab.comment frame', () => {
    const spy = vi.fn();
    websocketManager.bindQueryClient({ invalidateQueries: spy } as unknown as QueryClient);
    websocketManager.connect('tok');
    sock(0).triggerOpen();

    sock(0).triggerMessage({
      event: 'collab.comment',
      timestamp: 't',
      data: { query_id: 'q-9', comment_id: 'c-1', change_type: 'ADDED', actor_id: 'a-1' },
    });

    expect(spy).toHaveBeenCalledWith({
      queryKey: ['queries', 'detail', 'q-9', 'comments'],
    });
  });

  it('stops replaying a room after leave', () => {
    websocketManager.connect('t1');
    websocketManager.send({ type: 'collab.join', query_id: 'q-1' });
    websocketManager.send({ type: 'collab.leave', query_id: 'q-1' });

    websocketManager.connect('t2');
    sock(1).triggerOpen();

    expect(sentFrames(sock(1))).not.toContainEqual({ type: 'collab.join', query_id: 'q-1' });
  });
});
