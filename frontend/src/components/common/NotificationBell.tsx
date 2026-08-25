import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Badge, Button, Dropdown, List, message, Popconfirm, Skeleton, Tooltip } from 'antd';
import { BellOutlined, DeleteOutlined } from '@ant-design/icons';
import {
  deleteAllNotifications,
  deleteNotification,
  fetchUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationKeys,
} from '@/api/notifications';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
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
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('notifications.error'))),
  });

  const markAll = useMutation({
    mutationFn: () => markAllNotificationsRead(),
    onSuccess: invalidate,
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('notifications.error'))),
  });

  const remove = useMutation({
    mutationFn: (id: string) => deleteNotification(id),
    onSuccess: invalidate,
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('notifications.error'))),
  });

  const removeAll = useMutation({
    mutationFn: () => deleteAllNotifications(),
    onSuccess: invalidate,
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('notifications.error'))),
  });

  const onRowClick = (item: UserNotification) => {
    if (!item.read) {
      markRead.mutate(item.id);
    }
    setOpen(false);
    const target = routeForNotification(item);
    if (target) {
      navigate(target);
    }
  };

  const unreadCount = unreadQuery.data?.count ?? 0;
  const items = listQuery.data?.content ?? [];

  const dropdown = (
    <div className="af-notif-panel" role="menu">
      <div className="af-notif-header">
        <span className="af-notif-title">{t('notifications.title')}</span>
        <span className="af-notif-actions">
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
          {items.length > 0 && (
            <Popconfirm
              title={t('notifications.delete_all_confirm')}
              okText={t('notifications.delete_all')}
              okButtonProps={{ danger: true, loading: removeAll.isPending }}
              cancelText={t('common.cancel')}
              onConfirm={() => removeAll.mutate()}
            >
              <Button type="link" size="small" danger disabled={removeAll.isPending}>
                {t('notifications.delete_all')}
              </Button>
            </Popconfirm>
          )}
        </span>
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
  // Access (JIT) events may target an API connector instead of a datasource; the
  // interpolation variable name stays `datasource` so existing locale strings keep working.
  const accessResource = payload.connector ?? payload.datasource ?? '—';
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
    case 'QUERY_ESCALATED':
      return t('notifications.events.QUERY_ESCALATED', { datasource });
    case 'QUERY_EXECUTED':
      return t('notifications.events.QUERY_EXECUTED', { datasource });
    case 'REVIEW_TIMEOUT':
      return t('notifications.events.REVIEW_TIMEOUT', { datasource });
    // #622 — without these cases the bell renders a contentless "New notification", since the
    // backend records an in-app row for every event regardless of what the UI knows about.
    case 'REVIEW_ESCALATED':
      return t('notifications.events.REVIEW_ESCALATED', { datasource });
    case 'REVIEW_NUDGE':
      return t('notifications.events.REVIEW_NUDGE', { datasource });
    case 'AI_HIGH_RISK':
      return t('notifications.events.AI_HIGH_RISK', { datasource });
    case 'API_REQUEST_SUBMITTED':
      return payload.submitter
        ? t('notifications.events.API_REQUEST_SUBMITTED', {
            submitter: payload.submitter_name ?? payload.submitter,
            datasource,
          })
        : t('notifications.events.API_REQUEST_SUBMITTED_no_submitter', { datasource });
    case 'API_REQUEST_APPROVED':
      return t('notifications.events.API_REQUEST_APPROVED', { datasource });
    case 'API_REQUEST_EXECUTED':
      return t('notifications.events.API_REQUEST_EXECUTED', { datasource });
    case 'API_REQUEST_FAILED':
      return t('notifications.events.API_REQUEST_FAILED', { datasource });
    case 'ACCESS_REQUEST_SUBMITTED':
      return payload.requester
        ? t('notifications.events.ACCESS_REQUEST_SUBMITTED', {
            requester: payload.requester,
            datasource: accessResource,
          })
        : t('notifications.events.ACCESS_REQUEST_SUBMITTED_no_requester', {
            datasource: accessResource,
          });
    case 'ACCESS_REQUEST_APPROVED':
      return t('notifications.events.ACCESS_REQUEST_APPROVED', { datasource: accessResource });
    case 'ACCESS_REQUEST_REJECTED':
      return t('notifications.events.ACCESS_REQUEST_REJECTED', { datasource: accessResource });
    case 'ACCESS_GRANT_EXPIRED':
      return t('notifications.events.ACCESS_GRANT_EXPIRED', { datasource: accessResource });
    case 'ACCESS_GRANT_REVOKED':
      return t('notifications.events.ACCESS_GRANT_REVOKED', { datasource: accessResource });
    // #625 — the resource name rides in the `datasource` payload field for both grant kinds.
    case 'GRANT_STALE':
      return t('notifications.events.GRANT_STALE', { datasource: accessResource });
    // #626 — the exporter rides in `submitter`, the classification list in
    // `export_classifications`.
    case 'SENSITIVE_RESULT_EXPORTED':
      return t('notifications.events.SENSITIVE_RESULT_EXPORTED', {
        datasource: accessResource,
        classifications: payload.export_classifications ?? '—',
      });
    // #695 — the pipeline name rides in the `datasource` payload field.
    case 'DEPLOYMENT_SUBMITTED':
      return payload.submitter
        ? t('notifications.events.DEPLOYMENT_SUBMITTED', {
            submitter: payload.submitter_name ?? payload.submitter,
            pipeline: datasource,
            environment: payload.environment ?? '—',
            version: payload.version ?? '—',
          })
        : t('notifications.events.DEPLOYMENT_SUBMITTED_no_submitter', { pipeline: datasource });
    case 'DEPLOYMENT_APPROVED':
      return t('notifications.events.DEPLOYMENT_APPROVED', {
        environment: payload.environment ?? '—',
        version: payload.version ?? '—',
      });
    case 'DEPLOYMENT_REJECTED':
      return t('notifications.events.DEPLOYMENT_REJECTED', {
        environment: payload.environment ?? '—',
        version: payload.version ?? '—',
      });
    case 'DEPLOYMENT_OUTCOME_FAILED':
      return t('notifications.events.DEPLOYMENT_OUTCOME_FAILED', {
        pipeline: datasource,
        outcome: payload.outcome ?? '—',
      });
    case 'DEPLOYMENT_BREAK_GLASS_EXECUTED':
      return payload.submitter
        ? t('notifications.events.DEPLOYMENT_BREAK_GLASS_EXECUTED', {
            submitter: payload.submitter_name ?? payload.submitter,
            pipeline: datasource,
          })
        : t('notifications.events.DEPLOYMENT_BREAK_GLASS_EXECUTED_no_submitter', {
            pipeline: datasource,
          });
    default:
      return t('notifications.events.fallback');
  }
}

export function routeForNotification(item: UserNotification): string | null {
  // #622 escalations and nudges are the only event types raised for BOTH queries and API
  // requests, so unlike every branch below them the event name alone does not say which queue the
  // recipient needs — `api_request_id` does. Without this check a stalled API request would send
  // the reviewer to the SQL queue, where it does not appear.
  if (item.event_type === 'REVIEW_ESCALATED' || item.event_type === 'REVIEW_NUDGE') {
    return item.api_request_id ? '/api-reviews' : '/reviews';
  }
  // Reviewer-targeted: lands on the review queue, not the submitter-only detail page.
  if (item.event_type === 'QUERY_SUBMITTED') {
    return '/reviews';
  }
  if (item.event_type === 'API_REQUEST_SUBMITTED') {
    return '/api-reviews';
  }
  if (item.event_type === 'ACCESS_REQUEST_SUBMITTED') {
    return '/admin/access-requests';
  }
  // Terminal access events are requester-targeted → the requester's own access page.
  if (
    item.event_type === 'ACCESS_REQUEST_APPROVED' ||
    item.event_type === 'ACCESS_REQUEST_REJECTED' ||
    item.event_type === 'ACCESS_GRANT_EXPIRED' ||
    item.event_type === 'ACCESS_GRANT_REVOKED'
  ) {
    return '/access-requests';
  }
  // Admin-targeted: the report is where the grant can actually be acted on, and it is where the
  // same event's email points.
  if (item.event_type === 'GRANT_STALE') {
    return '/admin/over-provisioned-access';
  }
  // Terminal API events are submitter-targeted → the API-request detail page.
  if (
    item.event_type === 'API_REQUEST_APPROVED' ||
    item.event_type === 'API_REQUEST_EXECUTED' ||
    item.event_type === 'API_REQUEST_FAILED'
  ) {
    return item.api_request_id ? `/api-requests/${item.api_request_id}` : null;
  }
  // #695 — deliberately unrouted: no deploygov UI exists yet, and falling through would produce
  // a dead /queries link (deployment notifications carry no query_request_id anyway).
  if (
    item.event_type === 'DEPLOYMENT_SUBMITTED' ||
    item.event_type === 'DEPLOYMENT_APPROVED' ||
    item.event_type === 'DEPLOYMENT_REJECTED' ||
    item.event_type === 'DEPLOYMENT_OUTCOME_FAILED' ||
    item.event_type === 'DEPLOYMENT_BREAK_GLASS_EXECUTED'
  ) {
    return null;
  }
  return item.query_request_id ? `/queries/${item.query_request_id}` : null;
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
