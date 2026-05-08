import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Badge, Button, Dropdown, List, message, Skeleton, Tooltip } from 'antd';
import { BellOutlined, DeleteOutlined } from '@ant-design/icons';
import {
  deleteNotification,
  fetchUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationKeys,
} from '@/api/notifications';
import type { UserNotification, UserNotificationPayload } from '@/types/api';
import './notification-bell.css';

const PAGE_SIZE = 20;

export function NotificationBell() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  const unreadQuery = useQuery({
    queryKey: notificationKeys.unreadCount(),
    queryFn: fetchUnreadCount,
    refetchInterval: 60_000,
  });

  const listQuery = useQuery({
    queryKey: notificationKeys.list({ page: 0, size: PAGE_SIZE }),
    queryFn: () => listNotifications({ page: 0, size: PAGE_SIZE }),
    enabled: open,
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
  };

  const markRead = useMutation({
    mutationFn: (id: string) => markNotificationRead(id),
    onSuccess: invalidate,
    onError: () => message.error(t('notifications.error')),
  });

  const markAll = useMutation({
    mutationFn: () => markAllNotificationsRead(),
    onSuccess: invalidate,
    onError: () => message.error(t('notifications.error')),
  });

  const remove = useMutation({
    mutationFn: (id: string) => deleteNotification(id),
    onSuccess: invalidate,
    onError: () => message.error(t('notifications.error')),
  });

  const onRowClick = (item: UserNotification) => {
    if (!item.read) {
      markRead.mutate(item.id);
    }
    setOpen(false);
    if (item.query_request_id) {
      navigate(`/queries/${item.query_request_id}`);
    }
  };

  const unreadCount = unreadQuery.data?.count ?? 0;
  const items = listQuery.data?.content ?? [];

  const dropdown = (
    <div className="af-notif-panel" role="menu">
      <div className="af-notif-header">
        <span className="af-notif-title">{t('notifications.title')}</span>
        {unreadCount > 0 && (
          <Button
            type="link"
            size="small"
            onClick={() => markAll.mutate()}
            disabled={markAll.isPending}
          >
            {t('notifications.mark_all_read')}
          </Button>
        )}
      </div>
      {listQuery.isLoading && open ? (
        <div style={{ padding: 12 }}>
          <Skeleton active paragraph={{ rows: 3 }} />
        </div>
      ) : items.length === 0 ? (
        <div className="af-notif-empty">{t('notifications.empty')}</div>
      ) : (
        <List
          className="af-notif-list"
          dataSource={items}
          renderItem={(item) => (
            <List.Item
              key={item.id}
              className={item.read ? 'af-notif-row read' : 'af-notif-row unread'}
              onClick={() => onRowClick(item)}
            >
              <div className="af-notif-row-body">
                {!item.read && <span className="af-notif-dot" aria-hidden />}
                <div className="af-notif-row-text">
                  <div className="af-notif-message">{renderMessage(item, t)}</div>
                  <div className="af-notif-time">
                    {formatRelative(item.created_at, i18n.language)}
                  </div>
                </div>
              </div>
              <Tooltip title={t('notifications.delete')}>
                <Button
                  type="text"
                  size="small"
                  aria-label={t('notifications.delete')}
                  icon={<DeleteOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    remove.mutate(item.id);
                  }}
                  disabled={remove.isPending}
                />
              </Tooltip>
            </List.Item>
          )}
        />
      )}
    </div>
  );

  return (
    <Dropdown
      open={open}
      onOpenChange={setOpen}
      trigger={['click']}
      placement="bottomRight"
      popupRender={() => dropdown}
    >
      <button className="af-icon-btn" aria-label={t('notifications.title')}>
        <Badge count={unreadCount} size="small" overflowCount={99}>
          <BellOutlined />
        </Badge>
      </button>
    </Dropdown>
  );
}

function renderMessage(
  item: UserNotification,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  const payload: UserNotificationPayload = item.payload ?? {};
  const datasource = payload.datasource ?? '—';
  switch (item.event_type) {
    case 'QUERY_SUBMITTED':
      return payload.submitter
        ? t('notifications.events.QUERY_SUBMITTED', {
            submitter: payload.submitter_name ?? payload.submitter,
            datasource,
          })
        : t('notifications.events.QUERY_SUBMITTED_no_submitter', { datasource });
    case 'QUERY_APPROVED':
      return t('notifications.events.QUERY_APPROVED', { datasource });
    case 'QUERY_REJECTED':
      return t('notifications.events.QUERY_REJECTED', { datasource });
    case 'REVIEW_TIMEOUT':
      return t('notifications.events.REVIEW_TIMEOUT', { datasource });
    case 'AI_HIGH_RISK':
      return t('notifications.events.AI_HIGH_RISK', { datasource });
    default:
      return t('notifications.events.fallback');
  }
}

function formatRelative(iso: string, locale: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const deltaSec = Math.round((then - Date.now()) / 1000);
  const abs = Math.abs(deltaSec);
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  if (abs < 60) return rtf.format(deltaSec, 'second');
  if (abs < 3600) return rtf.format(Math.round(deltaSec / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(deltaSec / 3600), 'hour');
  return rtf.format(Math.round(deltaSec / 86400), 'day');
}
