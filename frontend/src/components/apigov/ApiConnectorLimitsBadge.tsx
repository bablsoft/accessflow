import { Tag, Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import { formatMicros } from '@/utils/micros';
import type { ApiConnector } from '@/types/api';

type LimitFields = Pick<
  ApiConnector,
  | 'calls_per_minute'
  | 'calls_per_day'
  | 'per_user_share_percent'
  | 'cost_per_call_micros'
  | 'monthly_budget_micros'
>;

/**
 * The connector list's Limits column (#974): rendered from the list row alone — budget
 * *usage* needs a per-connector call and lives on the settings tab, never here (N+1).
 */
export function ApiConnectorLimitsBadge({ connector }: { connector: LimitFields }) {
  const { t, i18n } = useTranslation();
  const perMinute = connector.calls_per_minute ?? null;
  const perDay = connector.calls_per_day ?? null;
  const share = connector.per_user_share_percent ?? null;
  const price = connector.cost_per_call_micros ?? null;
  const budget = connector.monthly_budget_micros ?? null;
  if (perMinute === null && perDay === null && share === null && price === null && budget === null) {
    return <span>—</span>;
  }
  const parts: string[] = [];
  if (perMinute !== null) parts.push(t('apiGov.limits.badgePerMinute', { value: perMinute }));
  if (perDay !== null) parts.push(t('apiGov.limits.badgePerDay', { value: perDay }));
  const tooltip = [
    t('apiGov.limits.tooltipShare', {
      value: share === null ? t('apiGov.limits.unlimited') : `${share}%`,
    }),
    t('apiGov.limits.tooltipPrice', {
      value: price === null ? '—' : formatMicros(price, i18n.language),
    }),
    t('apiGov.limits.tooltipBudget', {
      value: budget === null ? t('apiGov.limits.unlimited') : formatMicros(budget, i18n.language),
    }),
  ];
  return (
    <Tooltip
      title={
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {tooltip.map((line) => (
            <span key={line}>{line}</span>
          ))}
        </div>
      }
    >
      <Tag data-testid="connector-limits-badge">
        {parts.length > 0 ? parts.join(' · ') : t('apiGov.limits.badgeNoRate')}
      </Tag>
    </Tooltip>
  );
}
