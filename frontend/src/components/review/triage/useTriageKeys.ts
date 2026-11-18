import { useEffect, useRef } from 'react';

export interface TriageKeyHandlers {
  /** Ids of the rows on screen, in order. */
  ids: string[];
  activeId: string | null;
  onActiveChange: (id: string) => void;
  onOpen?: (id: string) => void;
  onApprove?: (id: string) => void;
  onReject?: (id: string) => void;
  onHelp?: () => void;
  enabled?: boolean;
}

const TYPING = 'input, textarea, select, [contenteditable="true"], [role="dialog"], [role="combobox"]';

/**
 * Keyboard triage for a review queue: `J`/`K` (or the arrow keys) move, `Enter` opens, `A`
 * approves, `R` rejects, `?` shows the shortcut list. Inactive while the user is typing, while a
 * dialog is open, and for any chord with a modifier, so it never shadows a browser or app shortcut.
 */
export function useTriageKeys(handlers: TriageKeyHandlers): void {
  const ref = useRef(handlers);
  useEffect(() => {
    ref.current = handlers;
  });

  const enabled = handlers.enabled ?? true;
  useEffect(() => {
    if (!enabled) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
      const target = event.target as HTMLElement | null;
      if (target?.closest?.(TYPING) || document.querySelector('.ant-modal-wrap:not([style*="display: none"])')) {
        return;
      }
      const { ids, activeId, onActiveChange, onOpen, onApprove, onReject, onHelp } = ref.current;
      const index = activeId === null ? -1 : ids.indexOf(activeId);
      const move = (delta: number) => {
        if (ids.length === 0) return;
        const next = index < 0 ? 0 : Math.min(Math.max(index + delta, 0), ids.length - 1);
        onActiveChange(ids[next] as string);
      };
      switch (event.key) {
        case 'j':
        case 'J':
        case 'ArrowDown':
          move(1);
          break;
        case 'k':
        case 'K':
        case 'ArrowUp':
          move(-1);
          break;
        case 'Enter':
          if (index < 0 || !onOpen) return;
          onOpen(activeId as string);
          break;
        case 'a':
        case 'A':
          if (index < 0 || !onApprove) return;
          onApprove(activeId as string);
          break;
        case 'r':
        case 'R':
          if (index < 0 || !onReject) return;
          onReject(activeId as string);
          break;
        case '?':
          if (!onHelp) return;
          onHelp();
          break;
        default:
          return;
      }
      event.preventDefault();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [enabled]);
}

/** The row to select once `removedId` leaves the list: the one after it, else the one before. */
export function nextActiveId(ids: string[], removedId: string): string | null {
  const index = ids.indexOf(removedId);
  if (index < 0) return null;
  return ids[index + 1] ?? ids[index - 1] ?? null;
}
