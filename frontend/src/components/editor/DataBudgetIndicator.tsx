import { Alert, Progress } from 'antd';
import { DashboardOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { dataBudgetKeys, getMyDataBudgetStatus } from '@/api/dataBudgets';
import type { DataBudgetConsumption } from '@/types/api';
import { budgetNearlyUsed, formatWindow } from '@/utils/dataBudget';
import { formatBytes } from '@/utils/queryPlan';

const CARD_STYLE = {
  background: 'var(--bg-elev)',
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-md)',
  padding: 14,
} as const;

/**
 * The caller's remaining data-volume allowance on the selected datasource (#942), shown before
 * they submit. Renders nothing when no budget applies — or the standing cannot be read — so an
 * unbudgeted datasource looks exactly as it did before.
 */
export function DataBudgetIndicator({ dsId }: { dsId: string }) {
  const { t } = useTranslation();
  const statusQuery = useQuery({
    queryKey: dataBudgetKeys.mine(dsId),
    queryFn: () => getMyDataBudgetStatus(dsId),
    retry: false,
  });
  const status = statusQuery.data;
  if (!status || status.budgets.length === 0) {
    return null;
  }

  return (
    <div style={CARD_STYLE} data-testid="data-budget-indicator">
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <DashboardOutlined style={{ color: 'var(--fg-muted)' }} />
        <div style={{ fontWeight: 600, fontSize: 12 }}>{t('dataBudgets.indicator.title')}</div>
      </div>
      {status.exhausted ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 10 }}
          title={t('dataBudgets.indicator.exhausted_title')}
          description={
            status.breach_action === 'REJECT'
              ? t('dataBudgets.indicator.exhausted_reject')
              : t('dataBudgets.indicator.exhausted_review')
          }
        />
      ) : budgetNearlyUsed(status) ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 10 }}
          title={t('dataBudgets.indicator.nearly_used')}
        />
      ) : null}
      {status.budgets.map((budget) => (
        <BudgetLine key={budget.id} budget={budget} />
      ))}
    </div>
  );
}

function BudgetLine({ budget }: { budget: DataBudgetConsumption }) {
  const { t } = useTranslation();
  const window = formatWindow(t, budget.window_minutes);
  const parts: string[] = [];
  if (budget.max_rows != null) {
    parts.push(
      t('dataBudgets.indicator.rows_left', {
        remaining: (budget.remaining_rows ?? 0).toLocaleString(),
        limit: budget.max_rows.toLocaleString(),
      }),
    );
  }
  if (budget.max_bytes != null) {
    parts.push(
      t('dataBudgets.indicator.bytes_left', {
        remaining: formatBytes(budget.remaining_bytes ?? 0),
        limit: formatBytes(budget.max_bytes) ?? '',
      }),
    );
  }
  const percent = Math.min(100, budget.used_percent);
  return (
    <div style={{ marginBottom: 6 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 11 }}>
        <span style={{ fontWeight: 500 }}>{budget.name}</span>
        <span className="muted">{t('dataBudgets.indicator.per_window', { window })}</span>
      </div>
      <Progress
        percent={percent}
        size="small"
        status={budget.exhausted ? 'exception' : 'normal'}
        aria-label={t('dataBudgets.indicator.used_aria', { name: budget.name, percent })}
      />
      <div className="muted" style={{ fontSize: 11 }}>{parts.join(' · ')}</div>
    </div>
  );
}
