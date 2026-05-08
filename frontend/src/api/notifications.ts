import { apiClient } from './client';
import type {
  UnreadCountResponse,
  UserNotificationPage,
} from '@/types/api';

const BASE = '/api/v1/notifications';

export interface NotificationsListFilters {
  page?: number;
  size?: number;
}

export const notificationKeys = {
  all: ['notifications'] as const,
  list: (filters: NotificationsListFilters = {}) =>
    ['notifications', 'list', filters] as const,
  unreadCount: () => ['notifications', 'unread-count'] as const,
};

export async function listNotifications(
  filters: NotificationsListFilters = {},
): Promise<UserNotificationPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<UserNotificationPage>(BASE, { params });
  return data;
}

export async function fetchUnreadCount(): Promise<UnreadCountResponse> {
  const { data } = await apiClient.get<UnreadCountResponse>(`${BASE}/unread-count`);
  return data;
}

export async function markNotificationRead(id: string): Promise<void> {
  await apiClient.post(`${BASE}/${id}/read`);
}

export async function markAllNotificationsRead(): Promise<void> {
  await apiClient.post(`${BASE}/read-all`);
}

export async function deleteNotification(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
