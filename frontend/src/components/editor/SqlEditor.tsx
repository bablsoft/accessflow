import { useEffect, useRef } from 'react';
import { EditorState, type Extension } from '@codemirror/state';
import { EditorView, keymap, gutter, GutterMarker, lineNumbers } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { sql, PostgreSQL, MySQL, StandardSQL, type SQLConfig } from '@codemirror/lang-sql';
import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';
import { syntaxHighlighting, indentOnInput, bracketMatching } from '@codemirror/language';
import { autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete';
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
import type { DatasourceSchema, DbType, AiIssue } from '@/types/api';
import { formatSql } from '@/utils/sqlFormat';
import { activeSyntax, engineMode } from '@/utils/engineModes';
import { accessflowHighlight, editorTheme } from './codemirrorTheme';

class IssueMarker extends GutterMarker {
  constructor(private severity: AiIssue['severity']) {
    super();
  }
  override toDOM() {
    const span = document.createElement('span');
    span.style.cssText = `display:inline-block;width:8px;height:8px;border-radius:2px;margin-top:6px;background:${
      this.severity === 'CRITICAL' || this.severity === 'HIGH'
        ? 'var(--risk-high)'
        : 'var(--risk-med)'
    }`;
    return span;
  }
}

interface SqlEditorProps {
  value: string;
  onChange: (next: string) => void;
  schema?: DatasourceSchema;
  dbType?: DbType;
  /**
   * Active syntax id from the db_type's engine mode (see utils/engineModes.ts), e.g. 'shell' or
   * 'json' for MongoDB. Ignored for single-syntax (SQL) engines; invalid values fall back to the
   * mode's default.
   */
  syntax?: string;
  readOnly?: boolean;
  height?: number;
  issues?: AiIssue[];
}

export function SqlEditor({
  value,
  onChange,
  schema,
  dbType = 'POSTGRESQL',
  syntax,
  readOnly,
  height = 280,
  issues = [],
}: SqlEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);

  // The editor view is rebuilt only on schema/dbType/syntax/readOnly changes, so its update
  // listener would otherwise close over a stale `onChange`. Route every change through a ref so the
  // listener always invokes the latest callback — critical when the parent's onChange spreads other
  // live state (e.g. a request-group member's datasourceId) that a stale closure would clobber.
  const onChangeRef = useRef(onChange);
  useEffect(() => {
    onChangeRef.current = onChange;
  });

  useEffect(() => {
    if (!containerRef.current) return;

    const sqlSchema: SQLConfig['schema'] = schema
      ? Object.fromEntries(
          schema.schemas
            .flatMap((s) => s.tables)
            .map((tab) => [tab.name, tab.columns.map((c) => c.name)]),
        )
      : undefined;

    const issueGutter = gutter({
      lineMarker(_view, line) {
        const lineNum = _view.state.doc.lineAt(line.from).number;
        const found = issues.find((i) => (i.line ?? 1) === lineNum);
        return found ? new IssueMarker(found.severity) : null;
      },
      lineMarkerChange: () => true,
    });

    const mode = engineMode(dbType);
    const language = activeSyntax(mode, syntax).language;
    const languageExtension =
      language === 'json'
        ? json()
        : language === 'javascript'
          ? javascript()
          : sql({
              // CodeMirror has no N1QL dialect; StandardSQL covers the SQL++ keyword set.
              dialect:
                mode.sqlDialect === 'postgresql'
                  ? PostgreSQL
                  : mode.sqlDialect === 'n1ql'
                    ? StandardSQL
                    : MySQL,
              schema: sqlSchema,
            });

    const exts: Extension[] = [
      lineNumbers(),
      issueGutter,
      history(),
      indentOnInput(),
      bracketMatching(),
      closeBrackets(),
      autocompletion(),
      highlightSelectionMatches(),
      languageExtension,
      syntaxHighlighting(accessflowHighlight),
      editorTheme,
      keymap.of([
        ...defaultKeymap,
        ...historyKeymap,
        ...completionKeymap,
        ...closeBracketsKeymap,
        ...searchKeymap,
        indentWithTab,
        {
          key: 'Mod-Shift-f',
          run: (view) => {
            // SQL formatting only; engine-managed query languages are not reformatted here.
            if (!mode.canFormat) return false;
            const formatted = formatSql(view.state.doc.toString(), dbType);
            view.dispatch({
              changes: { from: 0, to: view.state.doc.length, insert: formatted },
            });
            return true;
          },
        },
      ]),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          onChangeRef.current(update.state.doc.toString());
        }
      }),
      EditorView.editable.of(!readOnly),
      EditorState.readOnly.of(!!readOnly),
    ];

    const state = EditorState.create({ doc: value, extensions: exts });
    const view = new EditorView({ state, parent: containerRef.current });
    viewRef.current = view;
    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // We intentionally rebuild only when schema/dbType/syntax/readOnly change; value
    // is reconciled below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema, dbType, syntax, readOnly, JSON.stringify(issues)]);

  // Sync external value changes (e.g. format)
  useEffect(() => {
    const v = viewRef.current;
    if (!v) return;
    const current = v.state.doc.toString();
    if (current !== value) {
      v.dispatch({ changes: { from: 0, to: current.length, insert: value } });
    }
  }, [value]);

  return (
    <div
      ref={containerRef}
      style={{
        height,
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius)',
        overflow: 'hidden',
      }}
    />
  );
}
