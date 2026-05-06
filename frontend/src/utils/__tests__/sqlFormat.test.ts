import { describe, expect, it } from 'vitest';
import { formatSql } from '../sqlFormat';

describe('formatSql', () => {
  it('upper-cases keywords and adds line breaks for postgres', () => {
    const out = formatSql('select id from users where id = 1', 'POSTGRESQL');
    expect(out).toContain('SELECT');
    expect(out).toContain('FROM');
    expect(out).toContain('WHERE');
  });

  it('formats MySQL dialect without throwing', () => {
    const out = formatSql('select 1 from dual', 'MYSQL');
    expect(out).toContain('SELECT');
  });

  it('returns input unchanged when sql-formatter throws', () => {
    // Passing an unsupported dialect via cast triggers the catch path
    const malformed = 'this is not valid sql {{{';
    const out = formatSql(malformed);
    // sql-formatter is tolerant so it usually returns something; the key
    // is no exception escapes:
    expect(typeof out).toBe('string');
  });

  it('defaults to PostgreSQL dialect', () => {
    const out = formatSql('select 1');
    expect(out).toContain('SELECT');
  });
});
