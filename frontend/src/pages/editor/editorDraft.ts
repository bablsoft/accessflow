/**
 * The unsent editor draft, kept so that leaving the page (to check a table, open a notification)
 * does not lose it. Stored in `sessionStorage`: it survives navigation and a reload of this tab,
 * and is gone when the tab closes — SQL can carry literal data, so it is not kept longer.
 */
export interface EditorDraft {
  datasourceId: string | null;
  sql: string;
  justification: string;
}

const key = (userId: string) => `af-editor-draft:${userId}`;

export function loadEditorDraft(userId: string | undefined): EditorDraft | null {
  if (!userId) return null;
  try {
    const raw = sessionStorage.getItem(key(userId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<EditorDraft>;
    if (typeof parsed.sql !== 'string' || typeof parsed.justification !== 'string') return null;
    if (!parsed.sql.trim() && !parsed.justification.trim()) return null;
    return {
      datasourceId: typeof parsed.datasourceId === 'string' ? parsed.datasourceId : null,
      sql: parsed.sql,
      justification: parsed.justification,
    };
  } catch {
    return null;
  }
}

/** Saves the draft, or removes it when there is nothing worth keeping. */
export function saveEditorDraft(userId: string | undefined, draft: EditorDraft): void {
  if (!userId) return;
  try {
    if (!draft.sql.trim() && !draft.justification.trim()) {
      sessionStorage.removeItem(key(userId));
    } else {
      sessionStorage.setItem(key(userId), JSON.stringify(draft));
    }
  } catch {
    // Storage can be unavailable (private mode, quota); the editor works without a draft.
  }
}

export function clearEditorDraft(userId: string | undefined): void {
  if (!userId) return;
  try {
    sessionStorage.removeItem(key(userId));
  } catch {
    // See saveEditorDraft.
  }
}
