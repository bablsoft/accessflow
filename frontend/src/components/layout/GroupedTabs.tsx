import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react';
import './layout.css';

export interface TabGroupItem {
  key: string;
  label: ReactNode;
}

export interface TabGroup {
  key: string;
  label: string;
  items: TabGroupItem[];
}

interface GroupedTabsProps {
  groups: TabGroup[];
  activeKey: string;
  onChange: (key: string) => void;
  ariaLabel: string;
  /** Id of the element showing the active tab's content. */
  panelId: string;
}

/** The id a tab gets, for the panel's `aria-labelledby`. */
export function groupedTabId(panelId: string, key: string) {
  return `${panelId}-tab-${key}`;
}

/**
 * A vertical tab list split into labelled groups — for a settings page with more tabs than fit in
 * one row. Every tab stays visible; arrow keys, Home and End move between them across groups.
 */
export function GroupedTabs({ groups, activeKey, onChange, ariaLabel, panelId }: GroupedTabsProps) {
  const headingPrefix = useId();
  const buttons = useRef(new Map<string, HTMLButtonElement>());
  const keys = groups.flatMap((group) => group.items.map((item) => item.key));

  // Below the breakpoint the list is a horizontal strip, so the active tab may start off-screen.
  useEffect(() => {
    buttons.current.get(activeKey)?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  }, [activeKey]);

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, key: string) => {
    const index = keys.indexOf(key);
    let next: number;
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowRight':
        next = (index + 1) % keys.length;
        break;
      case 'ArrowUp':
      case 'ArrowLeft':
        next = (index - 1 + keys.length) % keys.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = keys.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    const nextKey = keys[next];
    if (nextKey === undefined) return;
    onChange(nextKey);
    buttons.current.get(nextKey)?.focus();
  };

  return (
    <div className="af-grouped-tabs" role="tablist" aria-orientation="vertical" aria-label={ariaLabel}>
      {groups
        .filter((group) => group.items.length > 0)
        .map((group) => {
          const headingId = `${headingPrefix}-${group.key}`;
          return (
            <div key={group.key} className="af-grouped-tabs-group" role="group" aria-labelledby={headingId}>
              <div id={headingId} className="af-grouped-tabs-heading">
                {group.label}
              </div>
              {group.items.map((item) => {
                const selected = item.key === activeKey;
                return (
                  <button
                    key={item.key}
                    ref={(node) => {
                      if (node) buttons.current.set(item.key, node);
                      else buttons.current.delete(item.key);
                    }}
                    type="button"
                    role="tab"
                    id={groupedTabId(panelId, item.key)}
                    aria-selected={selected}
                    aria-controls={panelId}
                    tabIndex={selected ? 0 : -1}
                    className={selected ? 'af-grouped-tab af-grouped-tab-active' : 'af-grouped-tab'}
                    onClick={() => onChange(item.key)}
                    onKeyDown={(event) => onKeyDown(event, item.key)}
                  >
                    {item.label}
                  </button>
                );
              })}
            </div>
          );
        })}
    </div>
  );
}
