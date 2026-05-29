import { describe, expect, it } from 'vitest';
import { extractPlaceholders, substitutePlaceholders } from './sqlPlaceholders';

describe('extractPlaceholders', () => {
  it('returns an empty list for SQL with no placeholders', () => {
    expect(extractPlaceholders('SELECT * FROM users')).toEqual([]);
  });

  it('finds single :identifier tokens', () => {
    expect(extractPlaceholders('SELECT * FROM users WHERE id = :userId')).toEqual(['userId']);
  });

  it('deduplicates repeated placeholders', () => {
    expect(
      extractPlaceholders('WHERE country = :country OR fallback_country = :country'),
    ).toEqual(['country']);
  });

  it('returns multiple distinct placeholders', () => {
    expect(
      extractPlaceholders('SELECT * FROM t WHERE a = :a AND b = :b LIMIT :limit'),
    ).toEqual(['a', 'b', 'limit']);
  });

  it('ignores PostgreSQL :: cast operator', () => {
    expect(extractPlaceholders("SELECT now()::text, '5'::integer")).toEqual([]);
  });

  it('does not match a bare colon followed by digits or punctuation', () => {
    expect(extractPlaceholders("SELECT '12:34'::time")).toEqual([]);
  });
});

describe('substitutePlaceholders', () => {
  it('replaces a single placeholder', () => {
    expect(substitutePlaceholders('WHERE id = :id', { id: '42' })).toBe('WHERE id = 42');
  });

  it('replaces every occurrence of a placeholder', () => {
    expect(
      substitutePlaceholders('WHERE a = :x OR b = :x', { x: "'y'" }),
    ).toBe("WHERE a = 'y' OR b = 'y'");
  });

  it('leaves unknown placeholders untouched', () => {
    expect(substitutePlaceholders('WHERE id = :id AND name = :name', { id: '1' }))
      .toBe('WHERE id = 1 AND name = :name');
  });

  it('preserves PostgreSQL :: casts', () => {
    expect(substitutePlaceholders("SELECT '5'::int, :n", { n: '7' }))
      .toBe("SELECT '5'::int, 7");
  });

  it('returns the input unchanged when values is empty', () => {
    expect(substitutePlaceholders('SELECT :a, :b', {})).toBe('SELECT :a, :b');
  });
});
