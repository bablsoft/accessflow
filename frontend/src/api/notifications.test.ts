import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, delete: del },
}));

import * as notificationsApi from './notifications';

describe('api/notifications', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    del.mockReset();
  });

  it('listNotifications GETs /api/v1/notifications with no params by default', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 },
    });
    await notificationsApi.listNotifications();
    expect(get).toHaveBeenCalledWith('/api/v1/notifications', { params: {} });
  });

  it('listNotifications forwards page and size', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 2, size: 50, total_elements: 0, total_pages: 0 },
    });
    await notificationsApi.listNotifications({ page: 2, size: 50 });
    expect(get).toHaveBeenCalledWith('/api/v1/notifications', {
      params: { page: 2, size: 50 },
    });
  });

  it('fetchUnreadCount GETs /api/v1/notifications/unread-count', async () => {
    get.mockResolvedValueOnce({ data: { count: 4 } });
    const result = await notificationsApi.fetchUnreadCount();
    expect(get).toHaveBeenCalledWith('/api/v1/notifications/unread-count');
    expect(result.count).toBe(4);
  });

  it('markNotificationRead POSTs /api/v1/notifications/{id}/read', async () => {
    post.mockResolvedValueOnce({});
    await notificationsApi.markNotificationRead('n-1');
    expect(post).toHaveBeenCalledWith('/api/v1/notifications/n-1/read');
  });

  it('markAllNotificationsRead POSTs /api/v1/notifications/read-all', async () => {
    post.mockResolvedValueOnce({});
    await notificationsApi.markAllNotificationsRead();
    expect(post).toHaveBeenCalledWith('/api/v1/notifications/read-all');
  });

  it('deleteNotification DELETEs /api/v1/notifications/{id}', async () => {
    del.mockResolvedValueOnce({});
    await notificationsApi.deleteNotification('n-7');
    expect(del).toHaveBeenCalledWith('/api/v1/notifications/n-7');
  });

  it('notificationKeys produce stable factory output', () => {
    expect(notificationsApi.notificationKeys.all).toEqual(['notifications']);
    expect(notificationsApi.notificationKeys.unreadCount()).toEqual([
      'notifications',
      'unread-count',
    ]);
    expect(notificationsApi.notificationKeys.list({ page: 0, size: 20 })).toEqual([
      'notifications',
      'list',
      { page: 0, size: 20 },
    ]);
  });
});
