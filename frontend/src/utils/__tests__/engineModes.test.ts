import { describe, expect, it } from 'vitest';
import { DB_TYPE_COLOR, activeSyntax, engineMode, syntaxForQuery } from '../engineModes';
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
  'REDIS',
  'CASSANDRA',
  'SCYLLADB',
  'ELASTICSEARCH',
  'OPENSEARCH',
  'DYNAMODB',
  'NEO4J',
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
    expect(mode.supportsTextToSql).toBe(true);
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

  it('returns the Redis mode: shell highlighting, no formatter, table results', () => {
    const mode = engineMode('REDIS');

    expect(mode.syntaxes).toEqual([
      { value: 'cli', labelKey: 'editor.syntax_redis', language: 'javascript' },
    ]);
    expect(mode.sqlDialect).toBeUndefined();
    expect(mode.canFormat).toBe(false);
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

describe('supportsTextToSql', () => {
  // AF-439: text-to-query now supported for every shipped engine (NoSQL un-gated).
  it('is enabled for all relational and NoSQL engines', () => {
    for (const dbType of ALL_DB_TYPES) {
      expect(engineMode(dbType).supportsTextToSql, dbType).toBe(true);
    }
  });
});

describe('DB_TYPE_COLOR', () => {
  it('declares a colour for every db type', () => {
    for (const dbType of ALL_DB_TYPES) {
      expect(DB_TYPE_COLOR[dbType], dbType).toBeTruthy();
    }
  });
});

describe('syntaxForQuery (AF-451 — engine-native apply)', () => {
  it('picks MongoDB shell vs JSON by the leading character', () => {
    expect(syntaxForQuery('MONGODB', 'db.users.createIndex({ email: 1 })')).toBe('shell');
    expect(syntaxForQuery('MONGODB', '  { "createIndexes": "users" }')).toBe('json');
  });

  it('returns each NoSQL engine’s single native syntax', () => {
    expect(syntaxForQuery('CASSANDRA', 'CREATE INDEX ON users(email)')).toBe('cql');
    expect(syntaxForQuery('SCYLLADB', 'CREATE INDEX ON users(email)')).toBe('cql');
    expect(syntaxForQuery('NEO4J', 'CREATE INDEX FOR (u:User) ON (u.email)')).toBe('cypher');
    expect(syntaxForQuery('ELASTICSEARCH', '{ "search": "logs" }')).toBe('query_dsl');
    expect(syntaxForQuery('REDIS', 'GET user:1')).toBe('cli');
    expect(syntaxForQuery('COUCHBASE', 'CREATE INDEX ix ON b(email)')).toBe('sqlpp');
    expect(syntaxForQuery('DYNAMODB', 'SELECT * FROM t')).toBe('partiql');
  });

  it('returns sql for relational engines and the undefined fallback', () => {
    expect(syntaxForQuery('POSTGRESQL', 'CREATE INDEX idx ON users(email)')).toBe('sql');
    expect(syntaxForQuery('MYSQL', 'SELECT 1')).toBe('sql');
    expect(syntaxForQuery(undefined, 'SELECT 1')).toBe('sql');
  });
});
