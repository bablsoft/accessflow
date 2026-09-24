import { describe, expect, it } from 'vitest';
import {
  DENIED_COLUMNS_MAX,
  isQualifiedColumnRef,
  supportsDeniedColumns,
} from '@/utils/deniedColumns';

describe('isQualifiedColumnRef', () => {
  it('accepts table.column and schema.table.column', () => {
    expect(isQualifiedColumnRef('customer.ssn')).toBe(true);
    expect(isQualifiedColumnRef('public.customer.ssn')).toBe(true);
  });

  it('rejects a bare column, four parts and blank parts', () => {
    expect(isQualifiedColumnRef('ssn')).toBe(false);
    expect(isQualifiedColumnRef('db.public.customer.ssn')).toBe(false);
    expect(isQualifiedColumnRef('customer.')).toBe(false);
    expect(isQualifiedColumnRef('. ssn')).toBe(false);
    expect(isQualifiedColumnRef('')).toBe(false);
  });
});

describe('supportsDeniedColumns', () => {
  it('covers the JSqlParser engines only', () => {
    expect(supportsDeniedColumns('POSTGRESQL')).toBe(true);
    expect(supportsDeniedColumns('CUSTOM')).toBe(true);
    expect(supportsDeniedColumns('MONGODB')).toBe(false);
    expect(supportsDeniedColumns('SNOWFLAKE')).toBe(false);
  });

  it('mirrors the backend size limit', () => {
    expect(DENIED_COLUMNS_MAX).toBe(200);
  });
});
