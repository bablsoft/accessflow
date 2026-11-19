import { useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
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

function NavigationBlocker({ when }: { when: boolean }) {
  const { modal } = App.useApp();
  const { t } = useTranslation();
  // Only a move to another page is a loss; a query-string change (a tab switch) is not.
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) => when && currentLocation.pathname !== nextLocation.pathname,
  );
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

/**
 * Warns before unsaved edits are lost: the browser's own prompt on reload or close, and a
 * confirmation before in-app navigation to another page.
 */
export function UnsavedChangesGuard({ when }: { when: boolean }) {
  // `useBlocker` only exists under a data router; a plain `<MemoryRouter>` (unit tests) has none.
  const dataRouter = useContext(UNSAFE_DataRouterContext);
  useEffect(() => {
    if (!when) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [when]);
  return dataRouter ? <NavigationBlocker when={when} /> : null;
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
