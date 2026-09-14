import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import '@/i18n';
import type { AiIssue, SqlReviewFinding } from '@/types/api';

const { setDiagnosticsSpy } = vi.hoisted(() => ({ setDiagnosticsSpy: vi.fn() }));

// The real lint package still runs; the spy only records what SqlEditor dispatches.
vi.mock('@codemirror/lint', async () => {
  const actual = await vi.importActual<typeof import('@codemirror/lint')>('@codemirror/lint');
  return {
    ...actual,
    setDiagnostics: (...args: Parameters<typeof actual.setDiagnostics>) => {
      setDiagnosticsSpy(...args);
      return actual.setDiagnostics(...args);
    },
  };
});

const { SqlEditor } = await import('./SqlEditor');

const finding: SqlReviewFinding = {
  rule_id: 'select_star',
  severity: 'BLOCK',
  statement_index: 0,
  line_number: 1,
  message: 'Star',
};
const issue: AiIssue = { severity: 'LOW', category: 'PERF', message: 'Slow', suggestion: 'Index' };

function lastDiagnostics(): unknown[] {
  const call = setDiagnosticsSpy.mock.calls.at(-1);
  return (call?.[1] ?? []) as unknown[];
}

describe('SqlEditor diagnostics (#865)', () => {
  it('mounts CodeMirror with the lint gutter and applies the initial diagnostics', () => {
    setDiagnosticsSpy.mockClear();
    const { container } = render(
      <SqlEditor value={'SELECT *\nFROM t'} onChange={() => {}} findings={[finding]} />,
    );
    expect(container.querySelector('.cm-editor')).not.toBeNull();
    expect(container.querySelector('.cm-gutter-lint')).not.toBeNull();
    expect(lastDiagnostics()).toEqual([
      expect.objectContaining({ from: 0, to: 8, severity: 'error', message: 'Star' }),
    ]);
  });

  it('pushes new diagnostics by transaction without rebuilding the editor view', () => {
    setDiagnosticsSpy.mockClear();
    const { container, rerender } = render(
      <SqlEditor value={'SELECT *\nFROM t'} onChange={() => {}} findings={[finding]} />,
    );
    const editorDom = container.querySelector('.cm-editor');
    const before = setDiagnosticsSpy.mock.calls.length;

    rerender(
      <SqlEditor
        value={'SELECT *\nFROM t'}
        onChange={() => {}}
        findings={[finding]}
        issues={[{ ...issue, line: 2 }]}
      />,
    );

    // Same DOM node → the EditorView survived; the change arrived as a setDiagnostics effect.
    expect(container.querySelector('.cm-editor')).toBe(editorDom);
    expect(setDiagnosticsSpy.mock.calls.length).toBe(before + 1);
    expect(lastDiagnostics().map((d) => (d as { severity: string; source: string }).source)).toEqual([
      'SQL review',
      'AI analysis',
    ]);
  });

  it('re-applies the latest diagnostics after a rebuild (dbType change)', () => {
    setDiagnosticsSpy.mockClear();
    const { container, rerender } = render(
      <SqlEditor value="SELECT 1" onChange={() => {}} dbType="POSTGRESQL" findings={[finding]} />,
    );
    const editorDom = container.querySelector('.cm-editor');

    rerender(<SqlEditor value="SELECT 1" onChange={() => {}} dbType="MYSQL" findings={[finding]} />);

    expect(container.querySelector('.cm-editor')).not.toBe(editorDom);
    expect(lastDiagnostics()).toHaveLength(1);
  });
});

describe('SqlEditor view lifecycle', () => {
  it('syncs an external value change into the doc while unfocused, and reports edits', async () => {
    const { EditorView } = await import('@codemirror/view');
    const onChange = vi.fn();
    const { container, rerender } = render(<SqlEditor value="SELECT 1" onChange={onChange} />);
    const view = EditorView.findFromDOM(container.querySelector('.cm-editor') as HTMLElement)!;
    expect(view.state.doc.toString()).toBe('SELECT 1');

    rerender(<SqlEditor value="SELECT 2" onChange={onChange} />);
    expect(view.state.doc.toString()).toBe('SELECT 2');

    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: 'SELECT 3' } });
    expect(onChange).toHaveBeenLastCalledWith('SELECT 3');
  });

  it('formats SQL on Mod-Shift-f for SQL engines only', async () => {
    const { EditorView } = await import('@codemirror/view');
    const { runScopeHandlers } = await import('@codemirror/view');
    const onChange = vi.fn();
    const { container } = render(<SqlEditor value="select id from t" onChange={onChange} />);
    const view = EditorView.findFromDOM(container.querySelector('.cm-editor') as HTMLElement)!;
    // jsdom reports no platform, so CodeMirror resolves Mod to Ctrl.
    const formatKey = () =>
      new KeyboardEvent('keydown', { key: 'F', keyCode: 70, shiftKey: true, ctrlKey: true });
    const handled = runScopeHandlers(view, formatKey(), 'editor');
    expect(handled).toBe(true);
    expect(view.state.doc.toString()).toContain('SELECT');

    const mongo = render(
      <SqlEditor value="db.users.find()" onChange={onChange} dbType="MONGODB" syntax="shell" />,
    );
    const mongoView = EditorView.findFromDOM(mongo.container.querySelector('.cm-editor') as HTMLElement)!;
    expect(runScopeHandlers(mongoView, formatKey(), 'editor')).toBe(false);
  });

  it('mounts the JSON and JavaScript languages for MongoDB syntaxes and honours readOnly', async () => {
    const { EditorView } = await import('@codemirror/view');
    const json = render(<SqlEditor value="{}" onChange={() => {}} dbType="MONGODB" syntax="json" readOnly />);
    const jsonView = EditorView.findFromDOM(json.container.querySelector('.cm-editor') as HTMLElement)!;
    expect(jsonView.state.readOnly).toBe(true);
    const shell = render(
      <SqlEditor value="db.x.find()" onChange={() => {}} dbType="MONGODB" syntax="shell" schema={{ schemas: [] }} />,
    );
    expect(shell.container.querySelector('.cm-editor')).not.toBeNull();
    const pg = render(
      <SqlEditor
        value="SELECT 1"
        onChange={() => {}}
        dbType="COUCHBASE"
        schema={{ schemas: [{ name: 'public', tables: [{ name: 't', columns: [{ name: 'id', type: 'int', nullable: false, primary_key: true }], foreign_keys: [] }] }] }}
      />,
    );
    expect(pg.container.querySelector('.cm-editor')).not.toBeNull();
  });
});
