import { describe, expect, it } from 'vitest';
import { deriveQueryType, mockAnalyze } from '../analyzer';

describe('mockAnalyze', () => {
  it('flags SELECT * with HIGH severity SELECT_STAR issue', () => {
    const r = mockAnalyze('SELECT * FROM users');
    expect(r.issues.some((i) => i.category === 'SELECT_STAR' && i.severity === 'HIGH')).toBe(true);
  });

  it('flags DROP TABLE with DESTRUCTIVE_DDL CRITICAL issue', () => {
    const r = mockAnalyze('DROP TABLE legacy_imports');
    // Risk score (10 base + 60 destructive = 70) lands in HIGH; the issue
    // itself is CRITICAL severity regardless.
    expect(r.risk_level).toBe('HIGH');
    expect(
      r.issues.some(
        (i) => i.category === 'DESTRUCTIVE_DDL' && i.severity === 'CRITICAL',
      ),
    ).toBe(true);
  });

  it('classifies DELETE-without-WHERE plus DROP as CRITICAL', () => {
    const r = mockAnalyze('DROP TABLE x; DELETE FROM y');
    expect(r.risk_level).toBe('CRITICAL');
  });

  it('flags TRUNCATE as destructive', () => {
    const r = mockAnalyze('TRUNCATE staging_data');
    expect(r.issues.some((i) => i.category === 'DESTRUCTIVE_DDL')).toBe(true);
  });

  it('flags DELETE without WHERE as CRITICAL NO_WHERE', () => {
    const r = mockAnalyze('DELETE FROM sessions');
    expect(r.issues.some((i) => i.category === 'NO_WHERE' && i.severity === 'CRITICAL')).toBe(true);
  });

  it('flags UPDATE without WHERE as HIGH NO_WHERE', () => {
    const r = mockAnalyze('UPDATE users SET email_verified = true');
    expect(r.issues.some((i) => i.category === 'NO_WHERE' && i.severity === 'HIGH')).toBe(true);
  });

  it('flags leading-wildcard LIKE as NON_SARGABLE', () => {
    const r = mockAnalyze("SELECT id FROM users WHERE name LIKE '%foo'");
    expect(r.issues.some((i) => i.category === 'NON_SARGABLE')).toBe(true);
  });

  it('flags SELECT without LIMIT as NO_LIMIT when no equality predicate', () => {
    const r = mockAnalyze('SELECT id FROM users');
    expect(r.issues.some((i) => i.category === 'NO_LIMIT')).toBe(true);
  });

  it('does not flag NO_LIMIT when an equality predicate is present', () => {
    const r = mockAnalyze('SELECT id FROM users WHERE id = 1');
    expect(r.issues.some((i) => i.category === 'NO_LIMIT')).toBe(false);
  });

  it('classifies LOW risk for a small bounded query', () => {
    const r = mockAnalyze('SELECT id FROM users WHERE id = 1 LIMIT 1');
    expect(r.risk_level).toBe('LOW');
    expect(r.issues).toHaveLength(0);
  });

  it('caps the risk score at 95', () => {
    const r = mockAnalyze("DROP TABLE x; DELETE FROM y; UPDATE z SET q=1; SELECT * FROM w WHERE name LIKE '%a'");
    expect(r.risk_score).toBeLessThanOrEqual(95);
  });

  it('returns line numbers for issues', () => {
    const r = mockAnalyze('SELECT 1\nFROM users\nWHERE id = 1');
    for (const issue of r.issues) {
      expect(issue.line).toBeGreaterThanOrEqual(1);
    }
  });

  it('attaches token estimates and affects_rows', () => {
    const r = mockAnalyze('SELECT * FROM customers');
    expect(r.prompt_tokens).toBe(412);
    expect(r.completion_tokens).toBe(187);
    expect(r.affects_rows).toBeGreaterThan(0);
  });

  it('returns a level summary for every level', () => {
    expect(mockAnalyze('SELECT id FROM users WHERE id = 1').summary).toMatch(/.+/);
    expect(mockAnalyze('UPDATE users SET email = NULL').summary).toMatch(/.+/);
    expect(mockAnalyze('SELECT * FROM customers').summary).toMatch(/.+/);
    // CRITICAL fires when score reaches 80+; chain destructive + no-WHERE
    expect(
      mockAnalyze('DROP TABLE legacy; DELETE FROM y').summary,
    ).toMatch(/CRITICAL/);
  });
});

describe('deriveQueryType', () => {
  it.each([
    ['SELECT 1', 'SELECT'],
    ['INSERT INTO x VALUES (1)', 'INSERT'],
    ['UPDATE x SET y = 1', 'UPDATE'],
    ['DELETE FROM x', 'DELETE'],
    ['CREATE TABLE x (id int)', 'DDL'],
    ['ALTER TABLE x ADD COLUMN y int', 'DDL'],
    ['DROP TABLE x', 'DDL'],
    ['TRUNCATE x', 'DDL'],
  ])('%s → %s', (sql, expected) => {
    expect(deriveQueryType(sql)).toBe(expected);
  });

  it('strips line comments before deriving the keyword', () => {
    expect(deriveQueryType('-- comment\nINSERT INTO x VALUES (1)')).toBe('INSERT');
  });

  it('falls back to SELECT when no keyword matches', () => {
    expect(deriveQueryType('-- nothing useful here')).toBe('SELECT');
  });
});
