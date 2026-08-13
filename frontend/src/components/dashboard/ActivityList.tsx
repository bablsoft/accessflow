import type { ReactNode } from 'react';
import { Skeleton, Typography } from 'antd';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { WidgetError } from '@/components/dashboard/WidgetError';

export interface ActivityListRow {
  /** Leading pills/badges (status, risk, type). */
  pills?: ReactNode;
  /** Main text; truncated with an ellipsis + tooltip when it overflows. */
  primary: ReactNode;
  /** Muted trailing metadata (submitter, time ago). */
  meta?: ReactNode;
  /** Trailing action (link or button). */
  action?: ReactNode;
}

interface ActivityListProps<T> {
  items: T[];
  loading: boolean;
  error?: unknown;
  onRetry?: () => void;
  errorMessageFor?: (err: unknown) => string;
  emptyIcon?: ReactNode;
  emptyTitle: string;
  rowKey: (item: T) => string;
  renderRow: (item: T) => ActivityListRow;
  /** Route for the footer "View all" link; omitted → no footer. */
  viewAllTo?: string;
  maxRows?: number;
}

/**
 * Shared row list for dashboard widgets (AF-498 redesign): aligned columns instead of wrapping
 * pill soup, single-line truncation with a tooltip, compact empty/error states, and an optional
 * "View all" footer to the full page.
 */
export function ActivityList<T>({
  items,
  loading,
  error,
  onRetry,
  errorMessageFor,
  emptyIcon,
  emptyTitle,
  rowKey,
  renderRow,
  viewAllTo,
  maxRows = 5,
}: ActivityListProps<T>) {
  const { t } = useTranslation();
  if (loading) {
    return <Skeleton active paragraph={{ rows: 3 }} />;
  }
  if (error !== undefined && error !== null) {
    return <WidgetError error={error} onRetry={onRetry} messageFor={errorMessageFor} />;
  }
  if (items.length === 0) {
    return <EmptyState size="sm" icon={emptyIcon} title={emptyTitle} />;
  }
  return (
    <div className="af-activity-list">
      {items.slice(0, maxRows).map((item) => {
        const row = renderRow(item);
        return (
          <div key={rowKey(item)} className="af-activity-row">
            {row.pills && <div className="af-activity-pills">{row.pills}</div>}
            <Typography.Text className="af-activity-primary" ellipsis={{ tooltip: true }}>
              {row.primary}
            </Typography.Text>
            {row.meta && (
              <span className="muted af-activity-meta">{row.meta}</span>
            )}
            {row.action && <div className="af-activity-action">{row.action}</div>}
          </div>
        );
      })}
      {viewAllTo && (
        <div className="af-activity-footer">
          <Link to={viewAllTo}>{t('dashboard.view_all')}</Link>
        </div>
      )}
    </div>
  );
}
