import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { EditorState, type Extension } from '@codemirror/state';
import { EditorView, keymap, lineNumbers } from '@codemirror/view';
import { lintGutter, linter, setDiagnostics } from '@codemirror/lint';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { sql, PostgreSQL, MySQL, StandardSQL, type SQLConfig } from '@codemirror/lang-sql';
import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';
import { syntaxHighlighting, indentOnInput, bracketMatching } from '@codemirror/language';
import { autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete';
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
import type { DatasourceSchema, DbType, AiIssue, SqlReviewFinding } from '@/types/api';
import { formatSql } from '@/utils/sqlFormat';
import { activeSyntax, engineMode } from '@/utils/engineModes';
import { accessflowHighlight, editorTheme } from './codemirrorTheme';
import { toDiagnostics } from './sqlReviewDiagnostics';

// Stable defaults so the diagnostics effect does not re-dispatch on every parent render.
const NO_ISSUES: readonly AiIssue[] = [];
const NO_FINDINGS: readonly SqlReviewFinding[] = [];

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
  /** AI-analysis issues, shown as diagnostics only while the analysis is fresh. */
  issues?: readonly AiIssue[];
  /** Live deterministic SQL review findings (#865), shown as diagnostics on the same surface. */
  findings?: readonly SqlReviewFinding[];
}

export function SqlEditor({
  value,
  onChange,
  schema,
  dbType = 'POSTGRESQL',
  syntax,
  readOnly,
  height = 280,
  issues = NO_ISSUES,
  findings = NO_FINDINGS,
}: SqlEditorProps) {
  const { t } = useTranslation();
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  // Doc + focus carried across view rebuilds (schema arrival, syntax toggle) so an in-progress
  // edit is never reset — the parent's `value` can lag the live doc by a render (#559).
  const restoreRef = useRef<{ doc: string; focus: boolean } | null>(null);

  // The editor view is rebuilt only on schema/dbType/syntax/readOnly changes, so its update
  // listener would otherwise close over a stale `onChange`. Route every change through a ref so the
  // listener always invokes the latest callback — critical when the parent's onChange spreads other
  // live state (e.g. a request-group member's datasourceId) that a stale closure would clobber.
  const onChangeRef = useRef(onChange);
  useEffect(() => {
    onChangeRef.current = onChange;
  });

  // Diagnostics are pushed by transaction, never by rebuilding the view: the live lint (#865)
  // answers on every typing pause, and tearing the EditorView down each time would wreck the typing
  // experience. The ref lets a rebuild (schema arrival, syntax toggle) re-apply the latest set.
  const diagnosticsRef = useRef({ issues, findings });
  useEffect(() => {
    diagnosticsRef.current = { issues, findings };
  });
  const applyDiagnostics = (
    view: EditorView,
    current: { issues: readonly AiIssue[]; findings: readonly SqlReviewFinding[] },
  ) => {
    view.dispatch(
      setDiagnostics(
        view.state,
        toDiagnostics(view.state.doc, {
          findings: current.findings,
          issues: current.issues,
          sources: {
            sqlReview: t('editor.sql_review_diagnostic_source'),
            ai: t('editor.ai_diagnostic_source'),
          },
        }),
      ),
    );
  };

  useEffect(() => {
    if (!containerRef.current) return;

    const sqlSchema: SQLConfig['schema'] = schema
      ? Object.fromEntries(
          schema.schemas
            .flatMap((s) => s.tables)
            .map((tab) => [tab.name, tab.columns.map((c) => c.name)]),
        )
      : undefined;

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
      // linter(null) installs the lint state without a lint source, so setDiagnostics() below is a
      // plain effect rather than an appendConfig that would be lost on the next rebuild.
      linter(null),
      lintGutter(),
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

    const restore = restoreRef.current;
    restoreRef.current = null;
    const state = EditorState.create({ doc: restore?.doc ?? value, extensions: exts });
    const view = new EditorView({ state, parent: containerRef.current });
    if (restore?.focus) {
      view.focus();
    }
    viewRef.current = view;
    applyDiagnostics(view, diagnosticsRef.current);
    return () => {
      restoreRef.current = { doc: view.state.doc.toString(), focus: view.hasFocus };
      view.destroy();
      viewRef.current = null;
    };
    // We intentionally rebuild only when schema/dbType/syntax/readOnly change; value is
    // reconciled below and diagnostics are dispatched by the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema, dbType, syntax, readOnly]);

  // Declared after the rebuild effect so that, on a commit that changes both, the new view exists
  // before the diagnostics land on it.
  useEffect(() => {
    const v = viewRef.current;
    if (!v) return;
    applyDiagnostics(v, { issues, findings });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [issues, findings]);

  // Sync external value changes (e.g. format, templates, applied AI drafts). Never while the
  // editor has focus: mid-typing the parent's `value` lags the live doc by a render, and writing
  // that stale value back would revert the newest keystrokes (visible in the group-member drawer,
  // where every keystroke re-renders the whole builder page — #559).
  useEffect(() => {
    const v = viewRef.current;
    if (!v || v.hasFocus) return;
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
