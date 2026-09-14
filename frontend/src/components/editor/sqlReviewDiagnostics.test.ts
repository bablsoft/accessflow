import { describe, expect, it } from 'vitest';
import { Text } from '@codemirror/state';
import type { AiIssue, SqlReviewFinding } from '@/types/api';
import { toDiagnostics } from './sqlReviewDiagnostics';

const sources = { sqlReview: 'SQL review', ai: 'AI analysis' };
const doc = Text.of(['SELECT *', 'FROM payroll.salaries', 'WHERE 1 = 1']);

const finding = (over: Partial<SqlReviewFinding>): SqlReviewFinding => ({
  rule_id: 'select_star',
  severity: 'WARN',
  statement_index: 0,
  line_number: 1,
  message: 'SELECT * fetches every column',
  ...over,
});

const issue = (over: Partial<AiIssue>): AiIssue => ({
  severity: 'MEDIUM',
  category: 'PERF',
  message: 'Full scan',
  suggestion: 'Add an index',
  ...over,
});

describe('toDiagnostics (#865)', () => {
  it('marks the whole line of a finding, WARN as warning and BLOCK as error', () => {
    const result = toDiagnostics(doc, {
      findings: [finding({}), finding({ rule_id: 'where_always_true', severity: 'BLOCK', line_number: 3 })],
      issues: [],
      sources,
    });
    expect(result).toEqual([
      { from: 0, to: 8, severity: 'warning', source: 'SQL review', message: 'SELECT * fetches every column' },
      { from: 31, to: 42, severity: 'error', source: 'SQL review', message: 'SELECT * fetches every column' },
    ]);
  });

  it('skips a finding without a line number — the strip lists it by statement', () => {
    expect(toDiagnostics(doc, { findings: [finding({ line_number: undefined })], issues: [], sources })).toEqual([]);
  });

  it('clamps a line past the end of the document onto the last line', () => {
    const [d] = toDiagnostics(doc, { findings: [finding({ line_number: 99 })], issues: [], sources });
    expect(d).toMatchObject({ from: 31, to: 42 });
    const [zero] = toDiagnostics(doc, { findings: [finding({ line_number: 0 })], issues: [], sources });
    expect(zero).toMatchObject({ from: 0, to: 8 });
  });

  it('maps AI issues: CRITICAL/HIGH as errors, MEDIUM/LOW as warnings, no line as line 1', () => {
    const result = toDiagnostics(doc, {
      findings: [],
      issues: [
        issue({ severity: 'CRITICAL', line: 2 }),
        issue({ severity: 'HIGH', line: 2 }),
        issue({ severity: 'LOW', line: 3 }),
        issue({ severity: 'MEDIUM' }),
      ],
      sources,
    });
    expect(result.map((d) => [d.from, d.severity, d.source])).toEqual([
      [0, 'warning', 'AI analysis'],
      [9, 'error', 'AI analysis'],
      [9, 'error', 'AI analysis'],
      [31, 'warning', 'AI analysis'],
    ]);
  });

  it('sorts findings and issues together by position', () => {
    const result = toDiagnostics(doc, {
      findings: [finding({ line_number: 3 })],
      issues: [issue({ line: 1 })],
      sources,
    });
    expect(result.map((d) => d.source)).toEqual(['AI analysis', 'SQL review']);
  });
});
