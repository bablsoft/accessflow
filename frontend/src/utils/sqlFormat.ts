import { format } from 'sql-formatter';
import type { DbType } from '@/types/api';

const dialectFor = (db: DbType): 'postgresql' | 'mysql' =>
  db === 'POSTGRESQL' ? 'postgresql' : 'mysql';

export function formatSql(sql: string, db: DbType = 'POSTGRESQL'): string {
  try {
    return format(sql, { language: dialectFor(db), keywordCase: 'upper' });
  } catch {
    return sql;
  }
}
