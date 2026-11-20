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
      className="u-flex u-col u-gap-1"
    >
      {rows.map(({ node, depth, key }) => {
        const rowsLabel = formatEstimatedRows(node.estimated_rows);
        const costLabel = formatCost(node.estimated_cost);
        return (
          <div
            key={key}
            role="treeitem"
            aria-level={depth + 1}
            className="u-bg-elev u-border u-rounded-sm" style={{ marginLeft: depth * 14, padding: '6px 8px' }}
          >
            <div className="u-flex u-items-baseline u-gap-2 u-wrap">
              <span className="mono u-text-xs u-fw-600">
                {node.operation}
              </span>
              {node.target && (
                <span className="mono muted u-text-2xs">
                  {node.target}
                </span>
              )}
              <div className="u-flex-1" />
              {rowsLabel && (
                <span className="mono muted u-text-2xs">
                  {t('dry_run_panel.rows_badge', { value: rowsLabel })}
                </span>
              )}
              {costLabel && (
                <span className="mono muted u-text-2xs">
                  {t('dry_run_panel.cost_badge', { value: costLabel })}
                </span>
              )}
            </div>
            {node.detail && (
              <div
                className="mono muted u-text-2xs u-mt-1 u-break-word"
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
