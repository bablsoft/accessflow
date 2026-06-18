import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import type { QueryPlanNode } from '@/types/api';
import { PlanTree } from './PlanTree';

const plan: QueryPlanNode = {
  operation: 'Nested Loop',
  estimated_rows: 150,
  estimated_cost: 25.5,
  children: [
    {
      operation: 'Seq Scan',
      target: 'users',
      estimated_rows: 100,
      estimated_cost: 12.5,
      detail: '(age > 21)',
      children: [],
    },
    { operation: 'Index Scan', target: 'orders_idx', estimated_rows: 5, children: [] },
  ],
};

describe('PlanTree', () => {
  it('renders every operation and target as tree items', () => {
    render(<PlanTree plan={plan} />);
    expect(screen.getByText('Nested Loop')).toBeInTheDocument();
    expect(screen.getByText('Seq Scan')).toBeInTheDocument();
    expect(screen.getByText('Index Scan')).toBeInTheDocument();
    expect(screen.getByText('users')).toBeInTheDocument();
    expect(screen.getByText('orders_idx')).toBeInTheDocument();
    expect(screen.getAllByRole('treeitem')).toHaveLength(3);
  });

  it('renders estimated-row and cost badges and the filter detail', () => {
    render(<PlanTree plan={plan} />);
    expect(screen.getByText('≈ 100 rows')).toBeInTheDocument();
    expect(screen.getByText('cost 25.5')).toBeInTheDocument();
    expect(screen.getByText('(age > 21)')).toBeInTheDocument();
  });

  it('marks tree depth via aria-level', () => {
    render(<PlanTree plan={plan} />);
    const items = screen.getAllByRole('treeitem');
    expect(items[0]).toHaveAttribute('aria-level', '1');
    expect(items[1]).toHaveAttribute('aria-level', '2');
  });
});
