import { describe, expect, it } from 'vitest';
import { formatRowSecurityPredicate } from './effectivePermission';

describe('formatRowSecurityPredicate', () => {
  it('renders a comparison with a quoted literal', () => {
    expect(formatRowSecurityPredicate('region', 'EQUALS', ['EU'])).toBe("region = 'EU'");
    expect(formatRowSecurityPredicate('amount', 'LESS_THAN_OR_EQUAL', [100])).toBe('amount <= 100');
    expect(formatRowSecurityPredicate('active', 'NOT_EQUALS', [false])).toBe('active <> false');
  });

  it('escapes a quote inside a literal', () => {
    expect(formatRowSecurityPredicate('owner', 'EQUALS', ["o'neil"])).toBe("owner = 'o''neil'");
  });

  it('renders IN / NOT IN as a list', () => {
    expect(formatRowSecurityPredicate('tenant_id', 'IN', ['a', 'b'])).toBe(
      "tenant_id IN ('a', 'b')",
    );
    expect(formatRowSecurityPredicate('tier', 'NOT_IN', [1])).toBe('tier NOT IN (1)');
  });

  it('leaves the value off when none resolved', () => {
    expect(formatRowSecurityPredicate('region', 'GREATER_THAN', [])).toBe('region >');
  });
});
