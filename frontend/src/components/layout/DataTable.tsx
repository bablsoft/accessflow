import type { KeyboardEvent, MouseEvent, ReactNode } from 'react';
import { Skeleton, Table } from 'antd';
import type { TableProps } from 'antd';
import { EmptyState } from '@/components/common/EmptyState';
import './layout.css';

const INTERACTIVE = 'a, button, input, select, textarea, label, [role="button"], [role="menuitem"]';

export interface DataTableProps<T> extends Omit<TableProps<T>, 'loading' | 'pagination'> {
  /** Shows a skeleton instead of the table while the first page loads. */
  loading?: boolean;
  /** Title of the empty state; without it Ant Design's default placeholder is used. */
  emptyTitle?: string;
  emptyDescription?: string;
  emptyAction?: ReactNode;
  /** Makes rows activatable by click and by Enter. */
  onRowActivate?: (record: T) => void;
  /** Server pagination: zero-based `page`. Omit to show every row without a pager. */
  page?: number;
  pageSize?: number;
  total?: number;
  onPageChange?: (page: number) => void;
}

/** Ant Design `Table` with the app's defaults: size, overflow, pager, empty state, keyboard rows. */
export function DataTable<T extends object>({
  loading = false,
  emptyTitle,
  emptyDescription,
  emptyAction,
  onRowActivate,
  page,
  pageSize = 20,
  total,
  onPageChange,
  className,
  locale,
  onRow,
  ...rest
}: DataTableProps<T>) {
  if (loading) {
    return (
      <div className="af-data-table-loading" aria-busy="true">
        <Skeleton active paragraph={{ rows: 8 }} />
      </div>
    );
  }

  const pagination: TableProps<T>['pagination'] =
    page === undefined
      ? false
      : {
          current: page + 1,
          pageSize,
          total,
          showSizeChanger: false,
          onChange: (next) => onPageChange?.(next - 1),
        };

  return (
    <Table<T>
      size="middle"
      scroll={{ x: 'max-content' }}
      {...rest}
      className={['af-data-table', className].filter(Boolean).join(' ')}
      pagination={pagination}
      locale={
        emptyTitle
          ? {
              ...locale,
              emptyText: (
                <EmptyState
                  size="sm"
                  title={emptyTitle}
                  description={emptyDescription}
                  action={emptyAction}
                />
              ),
            }
          : locale
      }
      onRow={(record, index) => {
        const base = onRow?.(record, index) ?? {};
        if (!onRowActivate) return base;
        return {
          ...base,
          tabIndex: 0,
          className: ['af-row-clickable', base.className].filter(Boolean).join(' '),
          onClick: (event: MouseEvent<HTMLElement>) => {
            base.onClick?.(event);
            const target = event.target as HTMLElement;
            if (target.closest(INTERACTIVE)) return;
            onRowActivate(record);
          },
          onKeyDown: (event: KeyboardEvent<HTMLElement>) => {
            base.onKeyDown?.(event);
            if (event.key === 'Enter' && event.target === event.currentTarget) {
              onRowActivate(record);
            }
          },
        };
      }}
    />
  );
}
