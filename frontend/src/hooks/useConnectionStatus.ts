import { useSyncExternalStore } from 'react';
import { websocketManager, type ConnectionStatus } from '@/realtime/websocketManager';

/** The realtime socket's state, for the topbar indicator. */
export function useConnectionStatus(): ConnectionStatus {
  return useSyncExternalStore(websocketManager.subscribeStatus, websocketManager.getStatus);
}
