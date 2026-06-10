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
  sqlDialect?: 'postgresql' | 'mysql';
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
    supportsTextToSql: false,
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
