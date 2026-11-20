import { Alert, Drawer, Progress, Skeleton } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { StatusTag } from '@/components/layout';
import { EmptyState } from '@/components/common/EmptyState';
import { dataBudgetKeys, getUserDataBudgetUsage } from '@/api/dataBudgets';
import type { DataBudgetConsumption, DataBudgetStatus, User } from '@/types/api';
import { dataBudgetBreachActionLabel } from '@/utils/enumLabels';
import { formatWindow } from '@/utils/dataBudget';
import { formatBytes } from '@/utils/queryPlan';
import { userDisplay } from '@/utils/userDisplay';

interface Props {
  user: User | null;
  onClose: () => void;
}

/**
 * An admin's view of one user's data-volume usage (#942) against every budget that applies to them,
 * grouped by datasource. There is no user detail page, so the drawer opens from the users list.
 */
export function UserDataBudgetDrawer({ user, onClose }: Props) {
  const { t } = useTranslation();
  const usageQuery = useQuery({
    queryKey: user ? dataBudgetKeys.forUser(user.id) : ['data-budgets', 'user', 'idle'],
    queryFn: () => getUserDataBudgetUsage(user!.id),
    enabled: !!user,
  });

  return (
    <Drawer
      open={!!user}
      onClose={onClose}
      size={520}
      title={
        user
          ? t('dataBudgets.usage.title', { name: userDisplay(user.display_name, user.email) })
          : ''
      }
      destroyOnHidden
    >
      <div className="muted u-text-xs u-mb-4">
        {t('dataBudgets.usage.description')}
      </div>
      {usageQuery.isLoading ? (
        <Skeleton active paragraph={{ rows: 4 }} />
      ) : usageQuery.isError ? (
        <Alert type="error" showIcon title={t('dataBudgets.usage.load_error')} />
      ) : (usageQuery.data ?? []).length === 0 ? (
        <EmptyState size="sm" title={t('dataBudgets.usage.empty')} />
      ) : (
        (usageQuery.data ?? []).map((status) => (
          <DatasourceUsage key={status.datasource_id} status={status} />
        ))
      )}
    </Drawer>
  );
}

function DatasourceUsage({ status }: { status: DataBudgetStatus }) {
  const { t } = useTranslation();
  return (
    <div
      className="u-p-3 u-mb-3 u-border u-rounded-md"
    >
      <div className="u-flex u-items-center u-gap-2 u-mb-2">
        <span className="u-fw-600">{status.datasource_name ?? status.datasource_id}</span>
        {status.exhausted && <StatusTag tone="danger">{t('dataBudgets.usage.exhausted')}</StatusTag>}
      </div>
      {status.budgets.map((budget) => (
        <BudgetUsage key={budget.id} budget={budget} />
      ))}
    </div>
  );
}

function BudgetUsage({ budget }: { budget: DataBudgetConsumption }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (budget.max_rows != null) {
    parts.push(
      t('dataBudgets.usage.rows_used', {
        used: budget.used_rows.toLocaleString(),
        limit: budget.max_rows.toLocaleString(),
      }),
    );
  }
  if (budget.max_bytes != null) {
    parts.push(
      t('dataBudgets.usage.bytes_used', {
        used: formatBytes(budget.used_bytes),
        limit: formatBytes(budget.max_bytes) ?? '',
      }),
    );
  }
  const percent = Math.min(100, budget.used_percent);
  return (
    <div className="u-mb-2">
      <div className="u-flex u-justify-between u-gap-2 u-text-xs">
        <span>{budget.name}</span>
        <span className="muted">
          {t('dataBudgets.indicator.per_window', { window: formatWindow(t, budget.window_minutes) })}
          {' · '}
          {dataBudgetBreachActionLabel(t, budget.breach_action)}
        </span>
      </div>
      <Progress
        percent={percent}
        size="small"
        status={budget.exhausted ? 'exception' : 'normal'}
        aria-label={t('dataBudgets.indicator.used_aria', { name: budget.name, percent })}
      />
      <div className="muted u-text-2xs">{parts.join(' · ')}</div>
    </div>
  );
}
