import { describe, expect, it } from 'vitest';
import { rowsToTree, treeToRows, type ConditionTree } from './conditionTreeForm';

type Leaf = { type: 'flag'; on: boolean } | { type: 'tag'; value: string };
type Row = { operand: 'flag' | 'tag'; negate: boolean; value?: string; on?: boolean };

const rowToLeaf = (row: Row): Leaf =>
  row.operand === 'flag' ? { type: 'flag', on: row.on ?? false } : { type: 'tag', value: row.value ?? '' };
const leafToRow = (leaf: Leaf, negate: boolean): Row =>
  leaf.type === 'flag'
    ? { operand: 'flag', negate, on: leaf.on }
    : { operand: 'tag', negate, value: leaf.value };

describe('conditionTreeForm', () => {
  it('wraps rows in and / or and negated rows in not', () => {
    const rows: Row[] = [
      { operand: 'tag', negate: false, value: 'a' },
      { operand: 'flag', negate: true, on: true },
    ];
    expect(rowsToTree('ALL', rows, rowToLeaf)).toEqual({
      type: 'and',
      children: [
        { type: 'tag', value: 'a' },
        { type: 'not', child: { type: 'flag', on: true } },
      ],
    });
    expect(rowsToTree('ANY', rows, rowToLeaf)).toMatchObject({ type: 'or' });
  });

  it('round-trips a flat tree', () => {
    const rows: Row[] = [
      { operand: 'tag', negate: true, value: 'x' },
      { operand: 'flag', negate: false, on: false },
    ];
    const parsed = treeToRows(rowsToTree('ANY', rows, rowToLeaf), leafToRow);
    expect(parsed).toEqual({ matchType: 'ANY', rows, supported: true });
  });

  it('treats a missing tree as an empty supported builder', () => {
    expect(treeToRows<Leaf, Row>(null, leafToRow)).toEqual({
      matchType: 'ALL',
      rows: [],
      supported: true,
    });
    expect(treeToRows<Leaf, Row>(undefined, leafToRow).supported).toBe(true);
  });

  it('reads a bare leaf and a bare not-of-leaf as one ALL row', () => {
    expect(treeToRows<Leaf, Row>({ type: 'tag', value: 'v' }, leafToRow)).toEqual({
      matchType: 'ALL',
      rows: [{ operand: 'tag', negate: false, value: 'v' }],
      supported: true,
    });
    expect(
      treeToRows<Leaf, Row>({ type: 'not', child: { type: 'flag', on: true } }, leafToRow).rows,
    ).toEqual([{ operand: 'flag', negate: true, on: true }]);
  });

  it('flags nesting the flat builder cannot show', () => {
    const nested: ConditionTree<Leaf> = {
      type: 'and',
      children: [{ type: 'or', children: [{ type: 'tag', value: 'a' }] }],
    };
    expect(treeToRows(nested, leafToRow)).toEqual({ matchType: 'ALL', rows: [], supported: false });
    const notOfAnd: ConditionTree<Leaf> = {
      type: 'not',
      child: { type: 'and', children: [] },
    };
    expect(treeToRows(notOfAnd, leafToRow).supported).toBe(false);
    const orWithNotOfOr: ConditionTree<Leaf> = {
      type: 'or',
      children: [{ type: 'not', child: { type: 'or', children: [] } }],
    };
    expect(treeToRows(orWithNotOfOr, leafToRow)).toEqual({
      matchType: 'ANY',
      rows: [],
      supported: false,
    });
  });

  it('flags a leaf the row mapper refuses', () => {
    const refuse = (): Row | null => null;
    expect(treeToRows<Leaf, Row>({ type: 'tag', value: 'a' }, refuse).supported).toBe(false);
  });
});
