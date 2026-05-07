import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/store/authStore';
import { websocketManager } from './websocketManager';

export function RealtimeBridge(): null {
  const queryClient = useQueryClient();
  const accessToken = useAuthStore((s) => s.accessToken);

  useEffect(() => {
    websocketManager.bindQueryClient(queryClient);
  }, [queryClient]);

  useEffect(() => {
    if (accessToken) {
      websocketManager.connect(accessToken);
    } else {
      websocketManager.disconnect();
    }
    return () => {
      // Component unmount: drop the connection so we never leak across HMR reloads.
      websocketManager.disconnect();
    };
  }, [accessToken]);

  return null;
}
