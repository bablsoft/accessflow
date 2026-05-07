import { websocketManager } from '@/realtime/websocketManager';
import type { WsEventName, WsEventPayloadMap } from '@/types/ws';

export interface UseWebSocketReturn {
  subscribe: <E extends WsEventName>(
    event: E,
    handler: (data: WsEventPayloadMap[E]) => void,
  ) => () => void;
}

/**
 * Subscribe to realtime events from the backend WebSocket. The connection itself is owned
 * by `<RealtimeBridge />` mounted at the app root; calling this hook only registers a
 * listener. Subscribers receive payloads in addition to the manager's default
 * `queryClient.invalidateQueries` calls — it's safe to subscribe purely for side effects
 * such as toasts.
 *
 * @example
 *   const { subscribe } = useWebSocket();
 *   useEffect(() => subscribe('review.new_request', () => message.info('New review')), []);
 */
export function useWebSocket(): UseWebSocketReturn {
  return {
    subscribe(event, handler) {
      return websocketManager.subscribe(event, handler);
    },
  };
}
