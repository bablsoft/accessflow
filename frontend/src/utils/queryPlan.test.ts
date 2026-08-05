import { describe, expect, it } from 'vitest';
import type { QueryPlanNode } from '@/types/api';
import { countPlanNodes, flattenPlan, formatBytes, formatCost, formatEstimatedRows } from './queryPlan';

const tree: QueryPlanNode = {
  operation: 'Nested Loop',
  estimated_rows: 100,
  estimated_cost: 25,
  children: [
    { operation: 'Seq Scan', target: 'users', estimated_rows: 100, children: [] },
    {
      operation: 'Index Scan',
      target: 'orders',
      estimated_rows: 5,
      children: [{ operation: 'Bitmap', children: [] }],
    },
  ],
};

describe('flattenPlan', () => {
  it('returns an empty array for null/undefined', () => {
    expect(flattenPlan(null)).toEqual([]);
    expect(flattenPlan(undefined)).toEqual([]);
  });

  it('flattens depth-first with depth and stable keys', () => {
    const flat = flattenPlan(tree);
    expect(flat.map((f) => f.node.operation)).toEqual([
      'Nested Loop',
      'Seq Scan',
      'Index Scan',
      'Bitmap',
    ]);
    expect(flat.map((f) => f.depth)).toEqual([0, 1, 1, 2]);
    expect(flat.map((f) => f.key)).toEqual(['0', '0.0', '0.1', '0.1.0']);
  });

  it('handles a missing children array', () => {
    const node = { operation: 'X' } as QueryPlanNode;
    expect(flattenPlan(node)).toHaveLength(1);
  });
});

describe('countPlanNodes', () => {
  it('counts every node in the tree', () => {
    expect(countPlanNodes(tree)).toBe(4);
  });

  it('returns 0 when absent', () => {
    expect(countPlanNodes(null)).toBe(0);
  });
});

describe('formatEstimatedRows', () => {
  it('rounds and groups thousands', () => {
    expect(formatEstimatedRows(1234.6)).toBe('1,235');
  });

  it('returns null when absent', () => {
    expect(formatEstimatedRows(null)).toBeNull();
    expect(formatEstimatedRows(undefined)).toBeNull();
  });
});

describe('formatCost', () => {
  it('keeps at most two decimals', () => {
    expect(formatCost(25.555)).toBe('25.56');
  });

  it('returns null when absent', () => {
    expect(formatCost(undefined)).toBeNull();
  });
});

describe('formatBytes', () => {
  it('returns null when absent', () => {
    expect(formatBytes(null)).toBeNull();
    expect(formatBytes(undefined)).toBeNull();
  });

  it('keeps raw bytes below 1000', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(999)).toBe('999 B');
  });

  it('scales with decimal (SI) units to match warehouse billing math', () => {
    expect(formatBytes(1000)).toBe('1 KB');
    expect(formatBytes(10_500_000)).toBe('10.5 MB');
    expect(formatBytes(1_234_567_890)).toBe('1.23 GB');
    expect(formatBytes(2_000_000_000_000)).toBe('2 TB');
  });

  it('caps at PB for very large values', () => {
    expect(formatBytes(3_000_000_000_000_000_000)).toBe('3,000 PB');
  });
});
