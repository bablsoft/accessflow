import { useEffect, useRef } from 'react';
import { MergeView } from '@codemirror/merge';
import { EditorState, type Extension } from '@codemirror/state';
import { EditorView, lineNumbers } from '@codemirror/view';
import { sql, PostgreSQL, MySQL, StandardSQL } from '@codemirror/lang-sql';
import { syntaxHighlighting } from '@codemirror/language';
import type { DbType } from '@/types/api';
import { accessflowHighlight, editorTheme } from './codemirrorTheme';

interface SqlDiffViewProps {
  /** Left pane — the older / base version. */
  oldValue: string;
  /** Right pane — the newer / compared version. */
  newValue: string;
  dbType?: DbType;
  height?: number;
  /** Accessible labels for each read-only pane. */
  oldLabel?: string;
  newLabel?: string;
}

function dialectFor(dbType: DbType | undefined) {
  if (dbType === 'POSTGRESQL') return PostgreSQL;
  if (dbType === 'MYSQL' || dbType === 'MARIADB') return MySQL;
  return StandardSQL;
}

export function SqlDiffView({
  oldValue,
  newValue,
  dbType = 'POSTGRESQL',
  height = 360,
  oldLabel,
  newLabel,
}: SqlDiffViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current) return undefined;

    const shared: Extension[] = [
      lineNumbers(),
      sql({ dialect: dialectFor(dbType) }),
      syntaxHighlighting(accessflowHighlight),
      editorTheme,
      EditorView.editable.of(false),
      EditorState.readOnly.of(true),
      EditorView.lineWrapping,
    ];

    const view = new MergeView({
      a: {
        doc: oldValue,
        extensions: [...shared, EditorView.contentAttributes.of({ 'aria-label': oldLabel ?? 'old version' })],
      },
      b: {
        doc: newValue,
        extensions: [...shared, EditorView.contentAttributes.of({ 'aria-label': newLabel ?? 'new version' })],
      },
      parent: containerRef.current,
      gutter: true,
      highlightChanges: true,
      collapseUnchanged: { margin: 3, minSize: 6 },
    });

    return () => view.destroy();
  }, [oldValue, newValue, dbType, oldLabel, newLabel]);

  return (
    <div
      ref={containerRef}
      data-testid="sql-diff-view"
      style={{
        height,
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius)',
        overflow: 'auto',
      }}
    />
  );
}
