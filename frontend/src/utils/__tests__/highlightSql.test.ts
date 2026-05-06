import { describe, expect, it } from 'vitest';
import { highlightSql, sqlTokenColor } from '../highlightSql';

describe('highlightSql', () => {
  it('tokenizes keywords as kw', () => {
    const tokens = highlightSql('SELECT id');
    expect(tokens.find((t) => t.value === 'SELECT')?.kind).toBe('kw');
    expect(tokens.find((t) => t.value === 'id')?.kind).toBe('id');
  });

  it('tokenizes string literals', () => {
    const tokens = highlightSql("WHERE name = 'alice'");
    expect(tokens.find((t) => t.value === "'alice'")?.kind).toBe('string');
  });

  it('tokenizes numbers', () => {
    const tokens = highlightSql('LIMIT 100');
    expect(tokens.find((t) => t.value === '100')?.kind).toBe('num');
  });

  it('tokenizes line comments', () => {
    const tokens = highlightSql('-- this is a comment\nSELECT 1');
    const c = tokens.find((t) => t.kind === 'comment');
    expect(c?.value).toContain('-- this is a comment');
  });

  it('tokenizes function calls as fn', () => {
    // COUNT is a known keyword, so the keyword check wins; verify a non-keyword fn instead:
    const tokens = highlightSql('myfunc(1)');
    expect(tokens.find((t) => t.value === 'myfunc')?.kind).toBe('fn');
  });

  it('tokenizes operators and whitespace', () => {
    const tokens = highlightSql('a = b');
    expect(tokens.some((t) => t.kind === 'op' && t.value === '=')).toBe(true);
    expect(tokens.some((t) => t.kind === 'ws')).toBe(true);
  });

  it('handles unterminated strings without throwing', () => {
    expect(() => highlightSql("SELECT 'abc")).not.toThrow();
  });

  it('returns empty token list for empty input', () => {
    expect(highlightSql('')).toEqual([]);
  });

  it('handles decimal numbers', () => {
    const tokens = highlightSql('SET pct = 1.5');
    expect(tokens.find((t) => t.value === '1.5')?.kind).toBe('num');
  });
});

describe('sqlTokenColor', () => {
  it('maps each kind to a CSS variable', () => {
    expect(sqlTokenColor('kw')).toBe('var(--sql-keyword)');
    expect(sqlTokenColor('string')).toBe('var(--sql-string)');
    expect(sqlTokenColor('num')).toBe('var(--sql-number)');
    expect(sqlTokenColor('comment')).toBe('var(--sql-comment)');
    expect(sqlTokenColor('fn')).toBe('var(--sql-fn)');
    expect(sqlTokenColor('op')).toBe('var(--sql-op)');
  });

  it('falls back to inherit for unknown kinds', () => {
    expect(sqlTokenColor('id')).toBe('inherit');
    expect(sqlTokenColor('ws')).toBe('inherit');
  });
});
