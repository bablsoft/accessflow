import { describe, expect, it } from 'vitest';
import { javaReplacementToJs, maskingPreview, parseBoundaries } from './maskingPreview';

describe('maskingPreview', () => {
  it('returns empty string for empty input', () => {
    expect(maskingPreview('FULL', '')).toBe('');
    expect(maskingPreview('PARTIAL', '')).toBe('');
  });

  it('FULL replaces the whole value', () => {
    expect(maskingPreview('FULL', '4111111111111111')).toBe('***');
  });

  it('PARTIAL keeps the last N characters (default 4)', () => {
    expect(maskingPreview('PARTIAL', '4111111111111234')).toBe('************1234');
  });

  it('PARTIAL honours visible_suffix param', () => {
    expect(maskingPreview('PARTIAL', 'abcdef', { visible_suffix: '2' })).toBe('****ef');
  });

  it('PARTIAL masks everything when value no longer than window', () => {
    expect(maskingPreview('PARTIAL', '1234', { visible_suffix: '4' })).toBe('****');
    expect(maskingPreview('PARTIAL', 'ab', { visible_suffix: '4' })).toBe('**');
  });

  it('PARTIAL falls back to default on invalid param', () => {
    expect(maskingPreview('PARTIAL', 'abcdef', { visible_suffix: 'x' })).toBe('**cdef');
    expect(maskingPreview('PARTIAL', 'abcdef', { visible_suffix: '' })).toBe('**cdef');
  });

  it('HASH renders a 64-char hex digest shape', () => {
    expect(maskingPreview('HASH', 'secret')).toMatch(/^[0-9a-f]{64}$/);
  });

  it('EMAIL preserves first char and domain', () => {
    expect(maskingPreview('EMAIL', 'jane.doe@example.com')).toBe('j***@example.com');
  });

  it('EMAIL falls back to full mask when not email-shaped', () => {
    expect(maskingPreview('EMAIL', 'not-an-email')).toBe('***');
    expect(maskingPreview('EMAIL', '@nolocal.com')).toBe('***');
    expect(maskingPreview('EMAIL', 'nodomain@')).toBe('***');
  });

  it('FORMAT_PRESERVING keeps shape, masking digits and letters', () => {
    expect(maskingPreview('FORMAT_PRESERVING', '555-12-3456')).toBe('***-**-****');
    expect(maskingPreview('FORMAT_PRESERVING', 'AB-12 cd')).toBe('xx-** xx');
  });

  it('KEEP_FIRST keeps the first N characters', () => {
    expect(maskingPreview('KEEP_FIRST', '0912345678', { visible_prefix: '4' })).toBe('0912******');
    expect(maskingPreview('KEEP_FIRST', 'abcdef')).toBe('abcd**');
    expect(maskingPreview('KEEP_FIRST', 'abc', { visible_prefix: '4' })).toBe('***');
    expect(maskingPreview('KEEP_FIRST', 'abcdef', { visible_prefix: 'x' })).toBe('abcd**');
  });

  it('CONSTANT returns the replacement or the full mask', () => {
    expect(maskingPreview('CONSTANT', 'secret', { replacement: 'REDACTED' })).toBe('REDACTED');
    expect(maskingPreview('CONSTANT', 'secret')).toBe('***');
  });

  it('NULLIFY previews as null', () => {
    expect(maskingPreview('NULLIFY', 'secret')).toBeNull();
  });

  it('REGEX_REPLACE applies Java-style templates and fails closed', () => {
    expect(
      maskingPreview('REGEX_REPLACE', '0912345678', {
        pattern: '^(\\d{3})\\d+(\\d{2})$',
        replacement: '$1-XXXXX-$2',
      }),
    ).toBe('091-XXXXX-78');
    expect(
      maskingPreview('REGEX_REPLACE', 'jane@example.com', {
        pattern: '^[^@]+(?<domain>@.*)$',
        replacement: 'user${domain}',
      }),
    ).toBe('user@example.com');
    expect(maskingPreview('REGEX_REPLACE', 'abc', { pattern: '\\d', replacement: '#' })).toBe('***');
    expect(maskingPreview('REGEX_REPLACE', 'abc', { pattern: '(', replacement: '#' })).toBe('***');
    expect(maskingPreview('REGEX_REPLACE', 'abc', { pattern: 'a', replacement: '$' })).toBe('***');
    expect(maskingPreview('REGEX_REPLACE', 'abc', { pattern: 'a' })).toBe('***');
    expect(maskingPreview('REGEX_REPLACE', 'a'.repeat(4097), { pattern: 'a', replacement: 'b' })).toBe(
      '***',
    );
  });

  it('NUMERIC_BUCKET floors to a size or labels a boundary band', () => {
    expect(maskingPreview('NUMERIC_BUCKET', '54321.5', { bucket_size: '10000' })).toBe('50000');
    expect(maskingPreview('NUMERIC_BUCKET', '7.3', { bucket_size: '0.5' })).toBe('7');
    const bands = { boundaries: '18, 30, 65' };
    expect(maskingPreview('NUMERIC_BUCKET', '12', bands)).toBe('<18');
    expect(maskingPreview('NUMERIC_BUCKET', '42', bands)).toBe('[30, 65)');
    expect(maskingPreview('NUMERIC_BUCKET', '70', bands)).toBe('>=65');
    expect(maskingPreview('NUMERIC_BUCKET', 'n/a', bands)).toBe('***');
    expect(maskingPreview('NUMERIC_BUCKET', '12', { boundaries: '30,18' })).toBe('***');
    expect(maskingPreview('NUMERIC_BUCKET', '12')).toBe('***');
  });

  it('DATE_GENERALIZE truncates to the precision and fails closed', () => {
    expect(maskingPreview('DATE_GENERALIZE', '1987-05-17', { precision: 'YEAR' })).toBe('1987');
    expect(maskingPreview('DATE_GENERALIZE', '1987-05-17', { precision: 'QUARTER' })).toBe('1987-Q2');
    expect(maskingPreview('DATE_GENERALIZE', '1987-05-17', { precision: 'MONTH' })).toBe('1987-05');
    expect(maskingPreview('DATE_GENERALIZE', 'May 1987', { precision: 'YEAR' })).toBe('***');
    expect(maskingPreview('DATE_GENERALIZE', '1987-13-01', { precision: 'YEAR' })).toBe('***');
    expect(maskingPreview('DATE_GENERALIZE', '1987-05-17', { precision: 'DAY' })).toBe('***');
    expect(maskingPreview('DATE_GENERALIZE', '1987-05-17')).toBe('***');
  });
});

describe('javaReplacementToJs', () => {
  it('translates escapes and named groups', () => {
    expect(javaReplacementToJs('\\$1 ${name} $2 \\x')).toBe('$$1 $<name> $2 x');
  });

  it('returns null for templates Java would reject', () => {
    expect(javaReplacementToJs('trailing\\')).toBeNull();
    expect(javaReplacementToJs('${open')).toBeNull();
    expect(javaReplacementToJs('$x')).toBeNull();
  });
});

describe('parseBoundaries', () => {
  it('parses ascending lists and rejects malformed ones', () => {
    expect(parseBoundaries('1, 2.5 ,10')).toEqual([1, 2.5, 10]);
    expect(parseBoundaries('')).toBeNull();
    expect(parseBoundaries('2,1')).toBeNull();
    expect(parseBoundaries('1,,2')).toBeNull();
  });
});
