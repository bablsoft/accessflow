import { useEffect, useRef } from 'react';
import { type Extension } from '@codemirror/state';
import { EditorView, keymap, lineNumbers } from '@codemirror/view';
import { defaultKeymap, indentWithTab } from '@codemirror/commands';
import { sql, PostgreSQL, MySQL, StandardSQL } from '@codemirror/lang-sql';
import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';
import { syntaxHighlighting, indentOnInput, bracketMatching } from '@codemirror/language';
import { autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete';
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
import type { DbType } from '@/types/api';
import { formatSql } from '@/utils/sqlFormat';
import { activeSyntax, engineMode } from '@/utils/engineModes';
import type { QueryCollabProvider } from '@/realtime/collabProvider';
import { accessflowHighlight, editorTheme } from './codemirrorTheme';

interface CollaborativeSqlEditorProps {
  provider: QueryCollabProvider;
  dbType?: DbType;
  syntax?: string;
  height?: number;
  /** Reports the 1-based line range of the current selection, for anchoring a new comment. */
  onSelectionLines?: (startLine: number, endLine: number) => void;
}

/**
 * CodeMirror editor bound to a shared Yjs document via {@link QueryCollabProvider}. The Yjs binding
 * (yCollab) owns the content, undo history, and remote cursors/selections — so this component does
 * not take a controlled value/onChange. Used for live co-authoring of a query that is in review.
 */
export function CollaborativeSqlEditor({
  provider,
  dbType = 'POSTGRESQL',
  syntax,
  height = 280,
  onSelectionLines,
}: CollaborativeSqlEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;

    const mode = engineMode(dbType);
    const language = activeSyntax(mode, syntax).language;
    const languageExtension =
      language === 'json'
        ? json()
        : language === 'javascript'
          ? javascript()
          : sql({
              dialect:
                mode.sqlDialect === 'postgresql'
                  ? PostgreSQL
                  : mode.sqlDialect === 'n1ql'
                    ? StandardSQL
                    : MySQL,
            });

    const exts: Extension[] = [
      lineNumbers(),
      indentOnInput(),
      bracketMatching(),
      closeBrackets(),
      autocompletion(),
      highlightSelectionMatches(),
      languageExtension,
      syntaxHighlighting(accessflowHighlight),
      editorTheme,
      // yCollab brings the shared content, awareness cursors, and its own undo manager.
      provider.extension(),
      keymap.of([
        ...defaultKeymap,
        ...completionKeymap,
        ...closeBracketsKeymap,
        ...searchKeymap,
        indentWithTab,
        {
          key: 'Mod-Shift-f',
          run: (view) => {
            if (!mode.canFormat) return false;
            const formatted = formatSql(view.state.doc.toString(), dbType);
            view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: formatted } });
            return true;
          },
        },
      ]),
      EditorView.updateListener.of((update) => {
        if (update.selectionSet && onSelectionLines) {
          const range = update.state.selection.main;
          const startLine = update.state.doc.lineAt(range.from).number;
          const endLine = update.state.doc.lineAt(range.to).number;
          onSelectionLines(startLine, endLine);
        }
      }),
    ];

    const view = new EditorView({ doc: provider.text.toString(), extensions: exts, parent: containerRef.current });
    viewRef.current = view;
    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [provider, dbType, syntax]);

  return (
    <div
      ref={containerRef}
      data-testid="collaborative-sql-editor"
      style={{
        height,
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius)',
        overflow: 'hidden',
      }}
    />
  );
}
