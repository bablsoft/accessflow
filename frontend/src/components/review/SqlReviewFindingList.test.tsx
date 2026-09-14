import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import type { SqlReviewFinding } from '@/types/api';
import { SqlReviewFindingList } from './SqlReviewFindingList';

const findings: SqlReviewFinding[] = [
  { rule_id: 'select_star', severity: 'BLOCK', statement_index: 0, line_number: 3, message: 'Star' },
  { rule_id: 'dml_without_transaction', severity: 'WARN', statement_index: 1, message: 'No txn' },
];

describe('SqlReviewFindingList (#865)', () => {
  it('renders one row per finding with severity, position and the backend message', () => {
    render(<SqlReviewFindingList findings={findings} />);
    const rows = screen.getAllByTestId('sql-review-finding');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveAttribute('data-severity', 'BLOCK');
    expect(rows[0]).toHaveTextContent('Block');
    expect(rows[0]).toHaveTextContent('L3');
    expect(rows[0]).toHaveTextContent('Star');
    expect(rows[1]).toHaveAttribute('data-severity', 'WARN');
    expect(rows[1]).toHaveTextContent('Warn');
    expect(rows[1]).toHaveTextContent('No txn');
  });

  it('falls back to the one-based statement when the parser gave no line', () => {
    render(<SqlReviewFindingList findings={[findings[1]!]} density="compact" />);
    expect(screen.getByTestId('sql-review-finding')).toHaveTextContent('Statement 2');
  });
});
