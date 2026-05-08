import { describe, expect, it } from 'vitest';
import { userDisplay } from '../userDisplay';

describe('userDisplay', () => {
  it('returns the display name when present', () => {
    expect(userDisplay('Alice', 'alice@example.com')).toBe('Alice');
  });

  it('falls back to email when display name is undefined', () => {
    expect(userDisplay(undefined, 'alice@example.com')).toBe('alice@example.com');
  });

  it('falls back to email when display name is null', () => {
    expect(userDisplay(null, 'alice@example.com')).toBe('alice@example.com');
  });

  it('falls back to email when display name is the empty string', () => {
    expect(userDisplay('', 'alice@example.com')).toBe('alice@example.com');
  });

  it('falls back to email when display name is whitespace-only', () => {
    expect(userDisplay('   ', 'alice@example.com')).toBe('alice@example.com');
  });

  it('trims a display name with surrounding whitespace', () => {
    expect(userDisplay('  Alice  ', 'alice@example.com')).toBe('Alice');
  });

  it('trims the email fallback as well', () => {
    expect(userDisplay('', '  alice@example.com  ')).toBe('alice@example.com');
  });

  it('returns the empty string when both inputs are missing', () => {
    expect(userDisplay(null, null)).toBe('');
    expect(userDisplay(undefined, undefined)).toBe('');
    expect(userDisplay('', '')).toBe('');
    expect(userDisplay('   ', '   ')).toBe('');
  });
});
