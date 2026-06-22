import { apiClient } from './client';
import type { SerializedSubscription } from '@/utils/push';

const BASE = '/api/v1/push';

export async function getVapidPublicKey(): Promise<string> {
  const { data } = await apiClient.get<{ public_key: string }>(`${BASE}/vapid-public-key`);
  return data.public_key;
}

export async function subscribePush(
  subscription: SerializedSubscription,
  userAgent?: string,
): Promise<void> {
  await apiClient.post(`${BASE}/subscriptions`, {
    endpoint: subscription.endpoint,
    keys: subscription.keys,
    user_agent: userAgent ?? null,
  });
}

export async function unsubscribePush(endpoint: string): Promise<void> {
  await apiClient.delete(`${BASE}/subscriptions`, { data: { endpoint } });
}
