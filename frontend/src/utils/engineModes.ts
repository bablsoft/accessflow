import type { DbType } from '@/types/api';

/**
 * Editor + results behaviour registry keyed by db_type (AF-418). A new engine declares its
 * highlighting, formatting, text-to-SQL support, and default results view here — without
 * editing SqlEditor, QueryEditorPage, or QueryDetailPage. Unregistered db_types fall back
 * to the SQL mode.
 */
export type EditorLanguage = 'sql' | 'javascript' | 'json';

export interface EditorSyntaxOption {
  /** Stable syntax id, passed to SqlEditor as `syntax`. */
  value: string;
  /** i18n key for the syntax toggle label. */
  labelKey: string;
  /** Which CodeMirror language the editor mounts for this syntax. */
  language: EditorLanguage;
}

export interface EngineMode {
  /** Available editor syntaxes; [0] is the default. More than one renders a toggle. */
  syntaxes: EditorSyntaxOption[];
  /** CodeMirror SQL dialect; only meaningful when the active language is 'sql'. */
  sqlDialect?: 'postgresql' | 'mysql' | 'n1ql';
  /** Whether the SQL formatter (toolbar button + Cmd/Ctrl+Shift+F) applies. */
  canFormat: boolean;
  /** Whether the text-to-SQL bar may be offered (still gated by datasource settings). */
  supportsTextToSql: boolean;
  /** Which results view QueryResultsTable opens with. */
  defaultResultView: 'table' | 'json';
}

/** Admin tag colour per db_type — single source for ConnectorsPage / CustomDriversPage. */
export const DB_TYPE_COLOR: Record<DbType, string> = {
  POSTGRESQL: 'blue',
  MYSQL: 'orange',
  MARIADB: 'gold',
  ORACLE: 'red',
  MSSQL: 'cyan',
  CUSTOM: 'purple',
  MONGODB: 'green',
  COUCHBASE: 'volcano',
  REDIS: 'red',
  CASSANDRA: 'geekblue',
  SCYLLADB: 'lime',
  ELASTICSEARCH: 'magenta',
  OPENSEARCH: 'cyan',
  DYNAMODB: 'gold',
  NEO4J: 'geekblue',
  SNOWFLAKE: 'cyan',
  BIGQUERY: 'blue',
  DATABRICKS: 'volcano',
};

const SQL_SYNTAX: EditorSyntaxOption = {
  value: 'sql',
  labelKey: 'editor.syntax_label',
  language: 'sql',
};

const ENGINE_MODES: Partial<Record<DbType, EngineMode>> = {
  MONGODB: {
    syntaxes: [
      { value: 'shell', labelKey: 'editor.syntax_shell', language: 'javascript' },
      { value: 'json', labelKey: 'editor.syntax_json', language: 'json' },
    ],
    canFormat: false,
    supportsTextToSql: true,
    defaultResultView: 'json',
  },
  // SQL++ (N1QL) is SQL-shaped: SQL highlighting (CodeMirror has no N1QL dialect; StandardSQL
  // covers the keyword set), sql-formatter's native n1ql dialect, and tabular results by
  // default — the JSON document view stays one click away for nested documents.
  COUCHBASE: {
    syntaxes: [{ value: 'sqlpp', labelKey: 'editor.syntax_sqlpp', language: 'sql' }],
    sqlDialect: 'n1ql',
    canFormat: true,
    supportsTextToSql: true,
    defaultResultView: 'table',
  },
  // Redis is a command language, not SQL: shell-style highlighting (JavaScript covers the
  // redis-cli token shape), no formatter, tabular results by default. Text-to-query drafts
  // redis-cli commands (AF-439).
  REDIS: {
    syntaxes: [{ value: 'cli', labelKey: 'editor.syntax_redis', language: 'javascript' }],
    canFormat: false,
    supportsTextToSql: true,
    defaultResultView: 'table',
  },
  // CQL is SQL-shaped: SQL highlighting (CodeMirror has no CQL dialect; StandardSQL covers the
  // keyword set). No formatter — it is not CQL-aware (USING TTL, IF NOT EXISTS, collection
  // literals) — and tabular results by default. Text-to-query drafts CQL (AF-439).
  CASSANDRA: {
    syntaxes: [{ value: 'cql', labelKey: 'editor.syntax_cql', language: 'sql' }],
    canFormat: false,
    supportsTextToSql: true,
    defaultResultView: 'table',
  },
  // ScyllaDB is CQL-compatible — identical editor behaviour to Cassandra.
  SCYLLADB: {
    syntaxes: [{ value: 'cql', labelKey: 'editor.syntax_cql', language: 'sql' }],
    canFormat: false,
    supportsTextToSql: true,
    defaultResultView: 'table',
  },
  // Elasticsearch uses a JSON query envelope (the Query DSL): JSON highlighting, no formatter, and
  // the JSON document view by default (hits flatten to a table one click away). Text-to-query
  // drafts a Query DSL envelope (AF-439).
  ELASTICSEARCH: {
    syntaxes: [{ value: 'query_dsl', labelKey: 'editor.syntax_query_dsl', language: 'json' }],
    canFormat: false,
    supportsTextToSql: true,
    defaultResultView: 'json',
  },
  // OpenSearch is wire-compatible with Elasticsearch — identical editor behaviour.
  OPENSEARCH: {
    syntaxes: [{ value: 'query_dsl', labelKey: 'editor.syntax_query_dsl', language: 'json' }],
    canFormat: false,
    supportsTextToSql: true,
    defaultResultView: 'json',
  },
  // PartiQL is SQL-shaped: SQL highlighting (CodeMirror has no PartiQL dialect; StandardSQL covers
  // the keyword set), sql-formatter, and tabular results by default — the JSON document view stays
  // one click away for nested item attributes. Table-management commands are JSON, but the editor
  // mode is keyed to the dominant PartiQL surface.
  DYNAMODB: {
    syntaxes: [{ value: 'partiql', labelKey: 'editor.syntax_partiql', language: 'sql' }],
    sqlDialect: 'postgresql',
    canFormat: true,
    supportsTextToSql: true,
    defaultResultView: 'table',
  },
  // Cypher is clause-based and graph-shaped. CodeMirror has no Cypher pack in the stack yet
  // (follow-up), so JavaScript highlighting covers the closest token shape ({}, strings, $params,
  // backtick idents). No formatter — it is not Cypher-aware — and the JSON view by default since
  // nodes/relationships/paths are graph structures (a flattened table is one click away).
  // Text-to-query drafts Cypher (AF-439).
  NEO4J: {
    syntaxes: [{ value: 'cypher', labelKey: 'editor.syntax_cypher', language: 'javascript' }],
    canFormat: false,
    supportsTextToSql: true,
    defaultResultView: 'json',
  },
};

export function engineMode(dbType?: DbType): EngineMode {
  const registered = dbType ? ENGINE_MODES[dbType] : undefined;
  if (registered) return registered;
  return {
    syntaxes: [SQL_SYNTAX],
    sqlDialect: dbType === 'POSTGRESQL' ? 'postgresql' : 'mysql',
    canFormat: true,
    supportsTextToSql: true,
    defaultResultView: 'table',
  };
}

/** The syntax option to apply: the requested one when valid for the mode, else the default. */
export function activeSyntax(mode: EngineMode, syntax?: string): EditorSyntaxOption {
  return mode.syntaxes.find((s) => s.value === syntax) ?? mode.syntaxes[0] ?? SQL_SYNTAX;
}

/**
 * Editor syntax id for a draft `query` against `dbType` — the frontend mirror of the backend
 * `SystemPromptRenderer.syntaxFor`. MongoDB has two syntaxes, so a JSON-command draft (leading `{`)
 * picks `json` and a shell draft picks `shell`; every other engine has a single mode, so its default
 * is returned. Used when applying an AI optimization suggestion so the editor mounts the engine's
 * native mode for that statement (works for the NoSQL engines, not just SQL).
 */
export function syntaxForQuery(dbType: DbType | undefined, query: string): string {
  const mode = engineMode(dbType);
  if (dbType === 'MONGODB' && query.trimStart().startsWith('{')) return 'json';
  return mode.syntaxes[0]?.value ?? SQL_SYNTAX.value;
}
