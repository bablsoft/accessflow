import { describe, expect, it } from 'vitest';
import type { SqlReviewFinding } from '@/types/api';
import {
  SQL_REVIEW_DB_TYPES,
  SQL_REVIEW_MAX_SQL_LENGTH,
  countBlockingFindings,
  countWarningFindings,
  isSqlReviewSupported,
} from '../sqlReview';

const findings: SqlReviewFinding[] = [
  { rule_id: 'select_star', severity: 'BLOCK', statement_index: 0, line_number: 1, message: 'a' },
  { rule_id: 'missing_limit_on_select', severity: 'WARN', statement_index: 0, message: 'b' },
  { rule_id: 'protected_table', severity: 'BLOCK', statement_index: 0, message: 'c' },
];

describe('sqlReview utils (#865)', () => {
  it('covers exactly the in-process relational dialects', () => {
    expect(SQL_REVIEW_DB_TYPES).toEqual(['POSTGRESQL', 'MYSQL', 'MARIADB', 'ORACLE', 'MSSQL', 'CUSTOM']);
    expect(isSqlReviewSupported('POSTGRESQL')).toBe(true);
    expect(isSqlReviewSupported('MSSQL')).toBe(true);
    expect(isSqlReviewSupported('MONGODB')).toBe(false);
    expect(isSqlReviewSupported('SNOWFLAKE')).toBe(false);
    expect(isSqlReviewSupported(undefined)).toBe(false);
  });

  it('matches the backend body size limit', () => {
    expect(SQL_REVIEW_MAX_SQL_LENGTH).toBe(100_000);
  });

  it('counts findings by severity', () => {
    expect(countBlockingFindings(findings)).toBe(2);
    expect(countWarningFindings(findings)).toBe(1);
    expect(countBlockingFindings([])).toBe(0);
  });
});
