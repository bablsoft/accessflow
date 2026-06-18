import type { QueryPlanNode } from '@/types/api';

/** A plan node paired with its tree depth and a stable React key — for indented rendering. */
export interface FlatPlanNode {
  node: QueryPlanNode;
  depth: number;
  key: string;
}

/** Depth-first flatten of the dry-run plan tree (AF-445) for indented list rendering. */
export function flattenPlan(root: QueryPlanNode | null | undefined): FlatPlanNode[] {
  const out: FlatPlanNode[] = [];
  const walk = (node: QueryPlanNode, depth: number, path: string): void => {
    out.push({ node, depth, key: path });
    (node.children ?? []).forEach((child, i) => walk(child, depth + 1, `${path}.${i}`));
  };
  if (root) {
    walk(root, 0, '0');
  }
  return out;
}

/** Total number of nodes in the plan tree (0 when absent). */
export function countPlanNodes(root: QueryPlanNode | null | undefined): number {
  if (!root) {
    return 0;
  }
  return 1 + (root.children ?? []).reduce((sum, child) => sum + countPlanNodes(child), 0);
}

/** Rounded, thousands-separated estimated-row label, or `null` when the engine omits it. */
export function formatEstimatedRows(value: number | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  return Math.round(value).toLocaleString('en-US');
}

/** Cost label to at most two decimals, or `null` when absent. */
export function formatCost(value: number | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  return value.toLocaleString('en-US', { maximumFractionDigits: 2 });
}
