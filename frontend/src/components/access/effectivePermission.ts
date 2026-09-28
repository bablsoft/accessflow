import type { RowSecurityOperator } from '@/types/api';

const OPERATOR_SYMBOLS: Record<RowSecurityOperator, string> = {
  EQUALS: '=',
  NOT_EQUALS: '<>',
  LESS_THAN: '<',
  LESS_THAN_OR_EQUAL: '<=',
  GREATER_THAN: '>',
  GREATER_THAN_OR_EQUAL: '>=',
  IN: 'IN',
  NOT_IN: 'NOT IN',
};

function literal(value: unknown): string {
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return `'${String(value).replace(/'/g, "''")}'`;
}

/**
 * Renders a resolved row-security predicate as the SQL-like condition the proxy appends — e.g.
 * `region = 'EU'` or `tenant_id IN ('a', 'b')` — instead of raw policy JSON (#946).
 */
export function formatRowSecurityPredicate(
  column: string,
  operator: RowSecurityOperator,
  values: unknown[],
): string {
  const symbol = OPERATOR_SYMBOLS[operator] ?? operator;
  if (operator === 'IN' || operator === 'NOT_IN') {
    return `${column} ${symbol} (${values.map(literal).join(', ')})`;
  }
  return `${column} ${symbol} ${values.length > 0 ? literal(values[0]) : ''}`.trimEnd();
}
