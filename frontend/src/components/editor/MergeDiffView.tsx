import { useEffect, useRef } from 'react';
import { MergeView } from '@codemirror/merge';
import { EditorState, type Extension } from '@codemirror/state';
import { EditorView, lineNumbers } from '@codemirror/view';
import { syntaxHighlighting } from '@codemirror/language';
import { accessflowHighlight, editorTheme } from './codemirrorTheme';

const NO_EXTENSIONS: Extension[] = [];

export interface MergeDiffViewProps {
  /** Left pane — the older / base version. */
  oldValue: string;
  /** Right pane — the newer / compared version. */
  newValue: string;
  /** Language support (e.g. `sql()`, `json()`); omit for plain text. Keep the reference stable. */
  extensions?: Extension[];
  height?: number;
  /** Accessible labels for each read-only pane. */
  oldLabel?: string;
  newLabel?: string;
  testId?: string;
}

/** Read-only, language-agnostic side-by-side diff built on `@codemirror/merge`. */
export function MergeDiffView({
  oldValue,
  newValue,
  extensions = NO_EXTENSIONS,
  height = 360,
  oldLabel,
  newLabel,
  testId = 'merge-diff-view',
}: MergeDiffViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current) return undefined;

    const shared: Extension[] = [
      lineNumbers(),
      ...extensions,
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
  }, [oldValue, newValue, extensions, oldLabel, newLabel]);

  return (
    <div
      ref={containerRef}
      data-testid={testId}
      style={{
        height,
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius)',
        overflow: 'auto',
      }}
    />
  );
}
