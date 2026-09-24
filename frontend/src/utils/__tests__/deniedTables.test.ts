import { describe, expect, it } from 'vitest';
import {
  DENIED_SCHEMAS_MAX,
  DENIED_TABLES_MAX,
  deniedTableEntries,
  hasBlankEntry,
  isValidDeniedSchema,
  isValidDeniedTable,
} from '@/utils/deniedTables';

describe('deniedTables', () => {
  it('mirrors the backend list limits', () => {
    expect(DENIED_SCHEMAS_MAX).toBe(50);
    expect(DENIED_TABLES_MAX).toBe(200);
  });

  it('flags blank entries', () => {
    expect(hasBlankEntry(['crm.salary', '  '])).toBe(true);
    expect(hasBlankEntry(['crm.salary'])).toBe(false);
    expect(hasBlankEntry([])).toBe(false);
  });

  it('lists denied schemas as schema.* before denied tables', () => {
    expect(deniedTableEntries(['hr'], ['crm.salary'])).toEqual(['hr.*', 'crm.salary']);
    expect(deniedTableEntries(null, undefined)).toEqual([]);
  });

  it('accepts only denied schemas that are one plain name', () => {
    expect(isValidDeniedSchema('hr')).toBe(true);
    expect(isValidDeniedSchema('analytics.hr')).toBe(false);
    expect(isValidDeniedSchema('h*')).toBe(false);
    expect(isValidDeniedSchema(' ')).toBe(false);
  });

  it('accepts table, schema.table and schema.* as denied tables', () => {
    expect(isValidDeniedTable('salary')).toBe(true);
    expect(isValidDeniedTable('crm.salary')).toBe(true);
    expect(isValidDeniedTable('db.crm.salary')).toBe(true);
    expect(isValidDeniedTable('crm.*')).toBe(true);
    expect(isValidDeniedTable('sal*')).toBe(false);
    expect(isValidDeniedTable('crm..salary')).toBe(false);
    expect(isValidDeniedTable('*')).toBe(false);
  });
});
