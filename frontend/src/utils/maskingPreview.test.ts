import { describe, expect, it } from 'vitest';
import { maskingPreview } from './maskingPreview';

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
});
