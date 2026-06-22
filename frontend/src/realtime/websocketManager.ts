import type { QueryClient } from '@tanstack/react-query';
import { getWsUrl } from '@/config/runtimeConfig';
import {
  WS_EVENT_NAMES,
  type CollabOutboundFrame,
  type WsEnvelope,
  type WsEventName,
  type WsEventPayloadMap,
} from '@/types/ws';

type Handler<E extends WsEventName> = (data: WsEventPayloadMap[E]) => void;
type AnyHandler = Handler<WsEventName>;

const BACKOFF_SCHEDULE_MS = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000];

const isWsEventName = (value: unknown): value is WsEventName =>
  typeof value === 'string' && (WS_EVENT_NAMES as ReadonlyArray<string>).includes(value);

class WebSocketManager {
  private socket: WebSocket | null = null;
  private currentToken: string | null = null;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private intentionallyClosed = false;
  private queryClient: QueryClient | null = null;
  private readonly subscribers = new Map<WsEventName, Set<AnyHandler>>();
  // Query ids whose collaboration room we have joined, so we can re-join after a reconnect.
  private readonly joinedRooms = new Set<string>();

  bindQueryClient(client: QueryClient): void {
    this.queryClient = client;
  }

  connect(token: string): void {
    if (!token) return;
    if (this.socket && this.currentToken === token) return;
    this.intentionallyClosed = false;
    this.currentToken = token;
    this.closeSocket();
    this.openSocket();
  }

  disconnect(): void {
    this.intentionallyClosed = true;
    this.currentToken = null;
    this.reconnectAttempts = 0;
    this.joinedRooms.clear();
    if (this.reconnectTimer != null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.closeSocket();
  }

  subscribe<E extends WsEventName>(event: E, handler: Handler<E>): () => void {
    let set = this.subscribers.get(event);
    if (!set) {
      set = new Set();
      this.subscribers.set(event, set);
    }
    set.add(handler as AnyHandler);
    return () => {
      const current = this.subscribers.get(event);
      if (!current) return;
      current.delete(handler as AnyHandler);
      if (current.size === 0) this.subscribers.delete(event);
    };
  }

  /**
   * Sends a collaboration frame back over the socket. Returns false if the socket is not open
   * (the caller's CRDT layer is resilient to dropped frames and re-syncs on reconnect). Tracks
   * room membership so joins are replayed automatically after a reconnect.
   */
  send(frame: CollabOutboundFrame): boolean {
    if (frame.type === 'collab.join') this.joinedRooms.add(frame.query_id);
    if (frame.type === 'collab.leave') this.joinedRooms.delete(frame.query_id);
    return this.rawSend(frame);
  }

  private rawSend(frame: CollabOutboundFrame): boolean {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return false;
    try {
      this.socket.send(JSON.stringify(frame));
      return true;
    } catch {
      return false;
    }
  }

  private openSocket(): void {
    if (!this.currentToken) return;
    const base = getWsUrl();
    const url = `${base}?token=${encodeURIComponent(this.currentToken)}`;
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = ws;
    ws.onopen = () => {
      this.reconnectAttempts = 0;
      // Re-join any active collaboration rooms; peers re-sync state on the fresh join.
      for (const queryId of this.joinedRooms) {
        this.rawSend({ type: 'collab.join', query_id: queryId });
      }
    };
    ws.onmessage = (event: MessageEvent) => this.handleMessage(event.data);
    ws.onerror = () => {
      // Browsers don't expose useful error data; the close handler will follow.
    };
    ws.onclose = () => {
      this.socket = null;
      if (!this.intentionallyClosed && this.currentToken) {
        this.scheduleReconnect();
      }
    };
  }

  private closeSocket(): void {
    if (!this.socket) return;
    const sock = this.socket;
    this.socket = null;
    sock.onopen = null;
    sock.onmessage = null;
    sock.onerror = null;
    sock.onclose = null;
    try {
      sock.close(1000);
    } catch {
      // best-effort
    }
  }

  private scheduleReconnect(): void {
    const idx = Math.min(this.reconnectAttempts, BACKOFF_SCHEDULE_MS.length - 1);
    const delay = BACKOFF_SCHEDULE_MS[idx];
    this.reconnectAttempts += 1;
    if (this.reconnectAttempts > 3) {
      console.debug('[ws] reconnect attempt', this.reconnectAttempts, 'in', delay, 'ms');
    } else {
      console.warn('[ws] connection lost; reconnecting in', delay, 'ms');
    }
    if (this.reconnectTimer != null) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.intentionallyClosed || !this.currentToken) return;
      this.openSocket();
    }, delay);
  }

  private handleMessage(raw: unknown): void {
    if (typeof raw !== 'string') return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      console.warn('[ws] dropped non-JSON frame');
      return;
    }
    if (!parsed || typeof parsed !== 'object') return;
    const envelopeShape = parsed as { event?: unknown; data?: unknown };
    if (!isWsEventName(envelopeShape.event)) {
      console.warn('[ws] unknown event name', envelopeShape.event);
      return;
    }
    const envelope = parsed as WsEnvelope;
    this.invokeDefault(envelope);
    const handlers = this.subscribers.get(envelope.event);
    if (!handlers) return;
    for (const handler of handlers) {
      try {
        handler(envelope.data);
      } catch (err) {
        console.error('[ws] subscriber threw', err);
      }
    }
  }

  private invokeDefault(envelope: WsEnvelope): void {
    if (!this.queryClient) return;
    const queryId = (envelope.data as { query_id?: string }).query_id;
    switch (envelope.event) {
      case 'query.status_changed':
      case 'query.executed':
        if (queryId) this.queryClient.invalidateQueries({ queryKey: ['queries', 'detail', queryId] });
        this.queryClient.invalidateQueries({ queryKey: ['queries', 'list'] });
        break;
      case 'ai.analysis_complete':
        if (queryId) this.queryClient.invalidateQueries({ queryKey: ['queries', 'detail', queryId] });
        break;
      case 'review.new_request':
        this.queryClient.invalidateQueries({ queryKey: ['reviews', 'pending'] });
        break;
      case 'review.decision_made':
        this.queryClient.invalidateQueries({ queryKey: ['reviews', 'pending'] });
        if (queryId) this.queryClient.invalidateQueries({ queryKey: ['queries', 'detail', queryId] });
        break;
      case 'notification.created':
        this.queryClient.invalidateQueries({ queryKey: ['notifications', 'list'] });
        this.queryClient.invalidateQueries({ queryKey: ['notifications', 'unread-count'] });
        break;
      case 'anomaly.detected': {
        const anomalyId = (envelope.data as { anomaly_id?: string }).anomaly_id;
        this.queryClient.invalidateQueries({ queryKey: ['anomalies', 'list'] });
        this.queryClient.invalidateQueries({ queryKey: ['anomalies', 'badge'] });
        if (anomalyId) {
          this.queryClient.invalidateQueries({ queryKey: ['anomalies', 'detail', anomalyId] });
        }
        break;
      }
      case 'collab.comment':
        if (queryId) {
          this.queryClient.invalidateQueries({
            queryKey: ['queries', 'detail', queryId, 'comments'],
          });
        }
        break;
    }
  }
}

export const websocketManager = new WebSocketManager();
export type { Handler };

// Exposed for Playwright E2E (e2e/) — lets tests call .disconnect() to suppress
// realtime invalidation without flipping auth state.
if (typeof window !== 'undefined') {
  (window as unknown as { __websocketManager?: typeof websocketManager }).__websocketManager =
    websocketManager;
}
