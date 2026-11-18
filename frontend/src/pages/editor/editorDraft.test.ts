import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearEditorDraft, loadEditorDraft, saveEditorDraft } from './editorDraft';

const KEY = 'af-editor-draft:u1';

afterEach(() => {
  sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('editorDraft', () => {
  it('round-trips a draft per user', () => {
    saveEditorDraft('u1', { datasourceId: 'ds', sql: 'select 1', justification: 'why' });
    expect(loadEditorDraft('u1')).toEqual({ datasourceId: 'ds', sql: 'select 1', justification: 'why' });
    expect(loadEditorDraft('u2')).toBeNull();
  });

  it('removes the entry when there is nothing worth keeping', () => {
    saveEditorDraft('u1', { datasourceId: 'ds', sql: 'select 1', justification: '' });
    saveEditorDraft('u1', { datasourceId: 'ds', sql: '  ', justification: '' });
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  it('clears on demand', () => {
    saveEditorDraft('u1', { datasourceId: null, sql: 'x', justification: '' });
    clearEditorDraft('u1');
    expect(loadEditorDraft('u1')).toBeNull();
  });

  it('does nothing without a signed-in user', () => {
    saveEditorDraft(undefined, { datasourceId: null, sql: 'x', justification: '' });
    clearEditorDraft(undefined);
    expect(sessionStorage.length).toBe(0);
    expect(loadEditorDraft(undefined)).toBeNull();
  });

  it('ignores malformed, mistyped and blank stored values', () => {
    sessionStorage.setItem(KEY, '{not json');
    expect(loadEditorDraft('u1')).toBeNull();
    sessionStorage.setItem(KEY, JSON.stringify({ sql: 1, justification: 'x' }));
    expect(loadEditorDraft('u1')).toBeNull();
    sessionStorage.setItem(KEY, JSON.stringify({ sql: ' ', justification: '' }));
    expect(loadEditorDraft('u1')).toBeNull();
    sessionStorage.setItem(KEY, JSON.stringify({ sql: 'x', justification: '', datasourceId: 7 }));
    expect(loadEditorDraft('u1')).toEqual({ datasourceId: null, sql: 'x', justification: '' });
  });

  it('survives storage that throws', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('denied');
    });
    expect(() => saveEditorDraft('u1', { datasourceId: null, sql: 'x', justification: '' })).not.toThrow();
    expect(() => saveEditorDraft('u1', { datasourceId: null, sql: '', justification: '' })).not.toThrow();
    expect(() => clearEditorDraft('u1')).not.toThrow();
  });
});
