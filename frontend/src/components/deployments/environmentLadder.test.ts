import { describe, expect, it } from 'vitest';
import { nextSortOrder } from './environmentLadder';

describe('nextSortOrder', () => {
  it('is 0 for an empty pipeline', () => {
    expect(nextSortOrder([])).toBe(0);
  });

  it('is one past the highest existing position, not the row count', () => {
    expect(nextSortOrder([{ sort_order: 0 }, { sort_order: 10 }])).toBe(11);
    expect(nextSortOrder([{ sort_order: 3 }])).toBe(4);
  });
});
