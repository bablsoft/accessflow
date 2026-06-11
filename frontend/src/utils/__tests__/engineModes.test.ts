import { describe, expect, it } from 'vitest';
import { DB_TYPE_COLOR, activeSyntax, engineMode } from '../engineModes';
import type { DbType } from '@/types/api';

const ALL_DB_TYPES: DbType[] = [
  'POSTGRESQL',
  'MYSQL',
  'MARIADB',
  'ORACLE',
  'MSSQL',
  'CUSTOM',
  'MONGODB',
  'COUCHBASE',
];

describe('engineMode', () => {
  it('returns the MongoDB mode with shell/json syntaxes and a JSON results default', () => {
    const mode = engineMode('MONGODB');

    expect(mode.syntaxes.map((s) => s.value)).toEqual(['shell', 'json']);
    expect(mode.syntaxes[0]).toEqual({
      value: 'shell',
      labelKey: 'editor.syntax_shell',
      language: 'javascript',
    });
    expect(mode.syntaxes[1]).toEqual({
      value: 'json',
      labelKey: 'editor.syntax_json',
      language: 'json',
    });
    expect(mode.canFormat).toBe(false);
    expect(mode.supportsTextToSql).toBe(false);
    expect(mode.defaultResultView).toBe('json');
    expect(mode.sqlDialect).toBeUndefined();
  });

  it('returns the Couchbase SQL++ mode: SQL highlighting, n1ql formatting, table results', () => {
    const mode = engineMode('COUCHBASE');

    expect(mode.syntaxes).toEqual([
      { value: 'sqlpp', labelKey: 'editor.syntax_sqlpp', language: 'sql' },
    ]);
    expect(mode.sqlDialect).toBe('n1ql');
    expect(mode.canFormat).toBe(true);
    expect(mode.supportsTextToSql).toBe(true);
    expect(mode.defaultResultView).toBe('table');
  });

  it('falls back to the SQL mode for relational and unknown db types', () => {
    for (const dbType of ['POSTGRESQL', 'MYSQL', 'MARIADB', 'ORACLE', 'MSSQL', 'CUSTOM'] as DbType[]) {
      const mode = engineMode(dbType);
      expect(mode.syntaxes).toHaveLength(1);
      expect(mode.syntaxes[0]!.language).toBe('sql');
      expect(mode.canFormat).toBe(true);
      expect(mode.supportsTextToSql).toBe(true);
      expect(mode.defaultResultView).toBe('table');
    }
    expect(engineMode(undefined).syntaxes[0]!.language).toBe('sql');
  });

  it('picks the PostgreSQL dialect for POSTGRESQL and MySQL for everything else', () => {
    expect(engineMode('POSTGRESQL').sqlDialect).toBe('postgresql');
    expect(engineMode('MYSQL').sqlDialect).toBe('mysql');
    expect(engineMode('ORACLE').sqlDialect).toBe('mysql');
    expect(engineMode(undefined).sqlDialect).toBe('mysql');
  });
});

describe('activeSyntax', () => {
  it('returns the matching syntax option when valid for the mode', () => {
    const mode = engineMode('MONGODB');
    expect(activeSyntax(mode, 'json').value).toBe('json');
    expect(activeSyntax(mode, 'shell').value).toBe('shell');
  });

  it('falls back to the default syntax for unknown or absent values', () => {
    const mongo = engineMode('MONGODB');
    expect(activeSyntax(mongo, 'sql').value).toBe('shell');
    expect(activeSyntax(mongo, undefined).value).toBe('shell');
    const sql = engineMode('POSTGRESQL');
    expect(activeSyntax(sql, 'shell').value).toBe('sql');
  });
});

describe('DB_TYPE_COLOR', () => {
  it('declares a colour for every db type', () => {
    for (const dbType of ALL_DB_TYPES) {
      expect(DB_TYPE_COLOR[dbType], dbType).toBeTruthy();
    }
  });
});
