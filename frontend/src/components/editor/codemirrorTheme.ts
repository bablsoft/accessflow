import { EditorView } from '@codemirror/view';
import { HighlightStyle } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';

/** Shared AccessFlow SQL highlight palette — reused by the editor and the read-only diff view. */
export const accessflowHighlight = HighlightStyle.define([
  { tag: t.keyword, color: 'var(--sql-keyword)', fontWeight: '600' },
  { tag: [t.string, t.special(t.string)], color: 'var(--sql-string)' },
  { tag: t.number, color: 'var(--sql-number)' },
  { tag: t.comment, color: 'var(--sql-comment)', fontStyle: 'italic' },
  { tag: t.function(t.variableName), color: 'var(--sql-fn)' },
  { tag: t.operator, color: 'var(--sql-op)' },
  { tag: t.tagName, color: 'var(--sql-table)' },
  { tag: [t.atom, t.bool], color: 'var(--sql-keyword)' },
]);

/** Shared editor chrome theme (fonts, gutters, selection, autocomplete tooltip). */
export const editorTheme = EditorView.theme({
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
  // Lint surface (#865): the base theme ships hard-coded SVG markers and colours; restyle them on
  // the risk tokens so SQL review findings and AI issues match the rest of the product.
  '.cm-gutter-lint': { width: '14px' },
  '.cm-gutter-lint .cm-gutterElement': { padding: '0 3px' },
  '.cm-lint-marker': {
    content: 'none',
    width: '8px',
    height: '8px',
    borderRadius: '2px',
    marginTop: '6px',
  },
  '.cm-lint-marker-error': { background: 'var(--risk-high)' },
  '.cm-lint-marker-warning': { background: 'var(--risk-med)' },
  '.cm-lintRange': { backgroundImage: 'none', paddingBottom: '0' },
  '.cm-lintRange-error': { borderBottom: '2px solid var(--risk-high)' },
  '.cm-lintRange-warning': { borderBottom: '2px solid var(--risk-med)' },
  '.cm-lintRange-active': { background: 'var(--accent-bg)' },
  '.cm-tooltip-lint': {
    background: 'var(--bg-elev)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius)',
    fontFamily: 'var(--font-sans)',
    fontSize: '12px',
    color: 'var(--fg)',
    boxShadow: 'var(--shadow-md)',
    maxWidth: '420px',
  },
  '.cm-diagnostic-error': { borderLeft: '3px solid var(--risk-high)' },
  '.cm-diagnostic-warning': { borderLeft: '3px solid var(--risk-med)' },
  '.cm-diagnosticSource': { color: 'var(--fg-muted)', opacity: '1' },
});
