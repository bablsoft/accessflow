import { useEffect, useRef } from 'react';
import { EditorState, type Extension } from '@codemirror/state';
import { EditorView, keymap, gutter, GutterMarker, lineNumbers } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { sql, PostgreSQL, MySQL, type SQLConfig } from '@codemirror/lang-sql';
import { syntaxHighlighting, HighlightStyle, indentOnInput, bracketMatching } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';
import { autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete';
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
import type { DatasourceSchema, DbType, AiIssue } from '@/types/api';
import { formatSql } from '@/utils/sqlFormat';

const accessflowHighlight = HighlightStyle.define([
  { tag: t.keyword, color: 'var(--sql-keyword)', fontWeight: '600' },
  { tag: [t.string, t.special(t.string)], color: 'var(--sql-string)' },
  { tag: t.number, color: 'var(--sql-number)' },
  { tag: t.comment, color: 'var(--sql-comment)', fontStyle: 'italic' },
  { tag: t.function(t.variableName), color: 'var(--sql-fn)' },
  { tag: t.operator, color: 'var(--sql-op)' },
  { tag: t.tagName, color: 'var(--sql-table)' },
  { tag: [t.atom, t.bool], color: 'var(--sql-keyword)' },
]);

const editorTheme = EditorView.theme({
  '&': {
    fontSize: '13px',
    fontFamily: 'var(--font-mono)',
    height: '100%',
    background: 'var(--bg-code)',
    color: 'var(--fg)',
  },
  '.cm-content': { padding: '12px 8px', caretColor: 'var(--fg)' },
  '.cm-gutters': {
    background: 'var(--bg-sunken)',
    color: 'var(--fg-faint)',
    borderRight: '1px solid var(--border)',
    fontFamily: 'var(--font-mono)',
    fontSize: '11.5px',
  },
  '.cm-activeLineGutter': { background: 'var(--bg-hover)' },
  '.cm-cursor': { borderLeftColor: 'var(--fg)' },
  '.cm-selectionBackground, ::selection': { background: 'var(--accent-bg)' },
  '&.cm-focused .cm-selectionBackground, &.cm-focused ::selection': {
    background: 'var(--accent-bg)',
  },
  '.cm-line': { padding: '0 4px' },
  '.cm-scroller': { overflow: 'auto' },
  '.cm-tooltip-autocomplete': {
    background: 'var(--bg-elev)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius)',
    fontFamily: 'var(--font-sans)',
    boxShadow: 'var(--shadow-md)',
  },
  '.cm-tooltip-autocomplete > ul > li[aria-selected]': {
    background: 'var(--accent-bg)',
    color: 'var(--fg)',
  },
});

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
  readOnly?: boolean;
  height?: number;
  issues?: AiIssue[];
}

export function SqlEditor({
  value,
  onChange,
  schema,
  dbType = 'POSTGRESQL',
  readOnly,
  height = 280,
  issues = [],
}: SqlEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);

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

    const exts: Extension[] = [
      lineNumbers(),
      issueGutter,
      history(),
      indentOnInput(),
      bracketMatching(),
      closeBrackets(),
      autocompletion(),
      highlightSelectionMatches(),
      sql({ dialect: dbType === 'POSTGRESQL' ? PostgreSQL : MySQL, schema: sqlSchema }),
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
          onChange(update.state.doc.toString());
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
    // We intentionally rebuild only when schema/dbType/readOnly change; value
    // is reconciled below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema, dbType, readOnly, JSON.stringify(issues)]);

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
