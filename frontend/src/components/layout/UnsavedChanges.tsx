import {
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { App } from 'antd';
import { UNSAFE_DataRouterContext, useBlocker } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import './layout.css';

/** Tracks whether a form has edits that were not saved yet. */
export function useDirtyState() {
  const [dirty, setDirty] = useState(false);
  const markDirty = useCallback(() => setDirty(true), []);
  const markClean = useCallback(() => setDirty(false), []);
  return { dirty, markDirty, markClean };
}

// Every mounted guard with unsaved edits, keyed by instance. React Router allows one blocker at a
// time, so the guards share this registry and only the first of them mounts the blocker.
const dirtyGuards = new Map<number, { tabs: boolean }>();
const listeners = new Set<() => void>();
let nextGuardId = 0;
let leaderId = -1;

function publish() {
  const first = dirtyGuards.keys().next();
  leaderId = first.done ? -1 : first.value;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function tabOf(search: string) {
  return new URLSearchParams(search).get('tab');
}

function NavigationBlocker() {
  const { modal } = App.useApp();
  const { t } = useTranslation();
  // The registry is read when the navigation happens, so a form that was just saved (and cleared
  // with flushSync) no longer blocks. Moving to another page is always a loss; a `?tab=` change
  // is one only for a guard whose form is unmounted by the switch.
  const blocker = useBlocker(({ currentLocation, nextLocation }) => {
    if (dirtyGuards.size === 0) return false;
    if (currentLocation.pathname !== nextLocation.pathname) return true;
    const guardsTabs = [...dirtyGuards.values()].some((guard) => guard.tabs);
    return guardsTabs && tabOf(currentLocation.search) !== tabOf(nextLocation.search);
  });
  const blocked = blocker.state === 'blocked';
  useEffect(() => {
    if (!blocked) return;
    modal.confirm({
      title: t('common.unsaved_title'),
      content: t('common.unsaved_body'),
      okText: t('common.unsaved_discard'),
      cancelText: t('common.unsaved_stay'),
      okButtonProps: { danger: true },
      onOk: () => blocker.proceed(),
      onCancel: () => blocker.reset(),
    });
    // The blocker object changes identity on every render; `blocked` is the trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blocked]);
  return null;
}

interface UnsavedChangesGuardProps {
  when: boolean;
  /**
   * Also ask before a `?tab=` change. Set it when the form lives in a tab that is unmounted on a
   * switch; leave it off where the tab's content stays mounted and keeps its edits.
   */
  tabs?: boolean;
}

/**
 * Warns before unsaved edits are lost: the browser's own prompt on reload or close, and a
 * confirmation before in-app navigation to another page. Any number of guards may be mounted at
 * once.
 */
export function UnsavedChangesGuard({ when, tabs = false }: UnsavedChangesGuardProps) {
  // `useBlocker` only exists under a data router; a plain `<MemoryRouter>` (unit tests) has none.
  const dataRouter = useContext(UNSAFE_DataRouterContext);
  const [id] = useState(() => ++nextGuardId);
  const leader = useSyncExternalStore(subscribe, () => leaderId);
  // A layout effect, so `flushSync(markClean)` right before `navigate()` has already left.
  useLayoutEffect(() => {
    if (!when) return;
    dirtyGuards.set(id, { tabs });
    publish();
    return () => {
      dirtyGuards.delete(id);
      publish();
    };
  }, [when, tabs, id]);
  useEffect(() => {
    if (!when) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [when]);
  return dataRouter && leader === id ? <NavigationBlocker /> : null;
}

/** Asks before discarding unsaved input, for a form that is closed rather than navigated away from. */
export function useConfirmDiscard() {
  const { modal } = App.useApp();
  const { t } = useTranslation();
  return useCallback(
    (dirty: boolean, proceed: () => void) => {
      if (!dirty) {
        proceed();
        return;
      }
      modal.confirm({
        title: t('common.unsaved_title'),
        content: t('common.unsaved_close_body'),
        okText: t('common.unsaved_discard'),
        cancelText: t('common.unsaved_stay'),
        okButtonProps: { danger: true },
        onOk: proceed,
      });
    },
    [modal, t],
  );
}

/**
 * Dirty tracking for a form in a modal or drawer: `markDirty` goes on the form's
 * `onValuesChange`, and `close(fn)` asks before discarding typed input. Opening the dialog again
 * starts clean.
 */
export function useDirtyClose(open: boolean) {
  const [dirty, setDirty] = useState(false);
  const [wasOpen, setWasOpen] = useState(open);
  const confirmDiscard = useConfirmDiscard();
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) setDirty(false);
  }
  const markDirty = useCallback(() => setDirty(true), []);
  const close = useCallback(
    (proceed: () => void) => confirmDiscard(dirty, proceed),
    [confirmDiscard, dirty],
  );
  return { dirty, markDirty, close };
}

interface FormActionsProps {
  dirty?: boolean;
  children: ReactNode;
}

/** The save bar of a settings form: stays in view while the form scrolls. */
export function FormActions({ dirty = false, children }: FormActionsProps) {
  const { t } = useTranslation();
  return (
    <div className="af-form-actions">
      {children}
      {dirty && (
        <span className="af-form-actions-hint" role="status">
          {t('common.unsaved_hint')}
        </span>
      )}
    </div>
  );
}
