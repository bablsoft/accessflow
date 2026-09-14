import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import type { SqlReviewLintState } from '@/hooks/useSqlReviewLint';
import { SqlReviewFindingsStrip } from './SqlReviewFindingsStrip';

const clean: SqlReviewLintState = {
  supported: true,
  evaluating: false,
  findings: [],
  blockingCount: 0,
  unparseable: false,
};

const withFindings: SqlReviewLintState = {
  ...clean,
  findings: [
    { rule_id: 'select_star', severity: 'BLOCK', statement_index: 0, line_number: 1, message: 'Star' },
    { rule_id: 'missing_limit_on_select', severity: 'WARN', statement_index: 0, line_number: 1, message: 'Limit' },
    { rule_id: 'cross_join', severity: 'WARN', statement_index: 0, line_number: 2, message: 'Cross' },
  ],
  blockingCount: 1,
};

describe('SqlReviewFindingsStrip (#865)', () => {
  it('renders nothing for an unsupported engine, an empty editor, or a clean settled draft', () => {
    const { rerender } = render(
      <SqlReviewFindingsStrip state={{ ...withFindings, supported: false }} hasSql />,
    );
    expect(screen.queryByTestId('sql-review-strip')).not.toBeInTheDocument();
    rerender(<SqlReviewFindingsStrip state={withFindings} hasSql={false} />);
    expect(screen.queryByTestId('sql-review-strip')).not.toBeInTheDocument();
    rerender(<SqlReviewFindingsStrip state={clean} hasSql />);
    expect(screen.queryByTestId('sql-review-strip')).not.toBeInTheDocument();
  });

  it('lists the findings with blocking and warning counts and the escalation note', () => {
    render(<SqlReviewFindingsStrip state={withFindings} hasSql />);
    const strip = screen.getByTestId('sql-review-strip');
    expect(strip).toHaveTextContent('Rule findings');
    expect(screen.getByTestId('sql-review-blocking-count')).toHaveTextContent('1 blocking');
    expect(strip).toHaveTextContent('2 warnings');
    expect(screen.getAllByTestId('sql-review-finding')).toHaveLength(3);
    expect(strip).toHaveTextContent('will require human approval');
    expect(screen.queryByTestId('sql-review-unparseable')).not.toBeInTheDocument();
  });

  it('shows only the warning count and no escalation note when nothing blocks', () => {
    render(
      <SqlReviewFindingsStrip
        state={{ ...withFindings, findings: withFindings.findings.slice(1), blockingCount: 0 }}
        hasSql
      />,
    );
    const strip = screen.getByTestId('sql-review-strip');
    expect(screen.queryByTestId('sql-review-blocking-count')).not.toBeInTheDocument();
    expect(strip).toHaveTextContent('2 warnings');
    expect(strip).not.toHaveTextContent('human approval');
  });

  it('renders the quiet cannot-parse hint instead of an error', () => {
    render(<SqlReviewFindingsStrip state={{ ...clean, unparseable: true }} hasSql />);
    expect(screen.getByTestId('sql-review-unparseable')).toHaveTextContent("Can't parse this yet");
    expect(screen.queryAllByTestId('sql-review-finding')).toHaveLength(0);
  });

  it('shows the evaluating indicator while the previous findings stay on screen', () => {
    render(<SqlReviewFindingsStrip state={{ ...withFindings, evaluating: true }} hasSql />);
    expect(screen.getByTestId('sql-review-evaluating')).toHaveTextContent('Checking');
    expect(screen.getAllByTestId('sql-review-finding')).toHaveLength(3);
  });
});
