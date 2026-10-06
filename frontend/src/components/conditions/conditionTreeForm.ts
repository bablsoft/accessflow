/**
 * Flat-row ↔ condition-tree conversion shared by every guided condition builder (routing
 * policies, SQL review custom rules). The builder edits a single-level ALL/ANY list of leaf rows,
 * each optionally negated; the wire shape is the backend's `type`-discriminated tree.
 */
export type ConditionMatchType = 'ALL' | 'ANY';

export type ConditionCombinator<L> =
  | { type: 'and'; children: ConditionTree<L>[] }
  | { type: 'or'; children: ConditionTree<L>[] }
  | { type: 'not'; child: ConditionTree<L> };

export type ConditionTree<L> = L | ConditionCombinator<L>;

/** The fields every builder row carries, whatever its operand set. */
export interface ConditionRowBase<O extends string = string> {
  operand: O;
  negate: boolean;
}

export interface ParsedConditionRows<R> {
  matchType: ConditionMatchType;
  rows: R[];
  /** False when the tree is nested deeper than the flat builder can show. */
  supported: boolean;
}

function isCombinator<L>(node: ConditionTree<L>): node is ConditionCombinator<L> {
  const type = (node as { type?: unknown }).type;
  return type === 'and' || type === 'or' || type === 'not';
}

export function rowsToTree<L, R extends { negate: boolean }>(
  matchType: ConditionMatchType,
  rows: readonly R[],
  rowToLeaf: (row: R) => L,
): ConditionTree<L> {
  const children: ConditionTree<L>[] = rows.map((row) => {
    const leaf = rowToLeaf(row);
    return row.negate ? { type: 'not', child: leaf } : leaf;
  });
  return matchType === 'ANY' ? { type: 'or', children } : { type: 'and', children };
}

function childToRow<L, R>(
  child: ConditionTree<L>,
  leafToRow: (leaf: L, negate: boolean) => R | null,
): R | null {
  if (!isCombinator(child)) return leafToRow(child, false);
  if (child.type === 'not' && !isCombinator(child.child)) return leafToRow(child.child, true);
  return null;
}

/**
 * Best-effort conversion of a stored tree into the flat builder model. `leafToRow` may return
 * null for a leaf the builder cannot show; either that or a nested combinator flags the whole
 * tree `supported: false`.
 */
export function treeToRows<L, R>(
  tree: ConditionTree<L> | null | undefined,
  leafToRow: (leaf: L, negate: boolean) => R | null,
): ParsedConditionRows<R> {
  if (!tree) {
    return { matchType: 'ALL', rows: [], supported: true };
  }
  if (isCombinator(tree) && tree.type !== 'not') {
    const matchType: ConditionMatchType = tree.type === 'or' ? 'ANY' : 'ALL';
    const rows: R[] = [];
    for (const child of tree.children) {
      const row = childToRow(child, leafToRow);
      if (!row) {
        return { matchType, rows: [], supported: false };
      }
      rows.push(row);
    }
    return { matchType, rows, supported: true };
  }
  const single = childToRow(tree, leafToRow);
  if (!single) {
    return { matchType: 'ALL', rows: [], supported: false };
  }
  return { matchType: 'ALL', rows: [single], supported: true };
}
