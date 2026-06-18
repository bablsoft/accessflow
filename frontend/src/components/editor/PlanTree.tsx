import { useTranslation } from 'react-i18next';
import type { QueryPlanNode } from '@/types/api';
import { flattenPlan, formatCost, formatEstimatedRows } from '@/utils/queryPlan';

interface PlanTreeProps {
  plan: QueryPlanNode;
}

/** Renders a dry-run execution plan as an indented, read-only tree (AF-445). */
export function PlanTree({ plan }: PlanTreeProps) {
  const { t } = useTranslation();
  const rows = flattenPlan(plan);
  return (
    <div
      role="tree"
      aria-label={t('dry_run_panel.plan_label')}
      style={{ display: 'flex', flexDirection: 'column', gap: 4 }}
    >
      {rows.map(({ node, depth, key }) => {
        const rowsLabel = formatEstimatedRows(node.estimated_rows);
        const costLabel = formatCost(node.estimated_cost);
        return (
          <div
            key={key}
            role="treeitem"
            aria-level={depth + 1}
            style={{
              marginLeft: depth * 14,
              padding: '6px 8px',
              background: 'var(--bg-elev)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius-sm)',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, flexWrap: 'wrap' }}>
              <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>
                {node.operation}
              </span>
              {node.target && (
                <span className="mono muted" style={{ fontSize: 11 }}>
                  {node.target}
                </span>
              )}
              <div style={{ flex: 1 }} />
              {rowsLabel && (
                <span className="mono muted" style={{ fontSize: 10.5 }}>
                  {t('dry_run_panel.rows_badge', { value: rowsLabel })}
                </span>
              )}
              {costLabel && (
                <span className="mono muted" style={{ fontSize: 10.5 }}>
                  {t('dry_run_panel.cost_badge', { value: costLabel })}
                </span>
              )}
            </div>
            {node.detail && (
              <div
                className="mono muted"
                style={{ fontSize: 11, marginTop: 4, wordBreak: 'break-word' }}
              >
                {node.detail}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
