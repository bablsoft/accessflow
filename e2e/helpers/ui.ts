import type { Locator, Page } from '@playwright/test';

/**
 * The active AntD tab panel within `scope`.
 *
 * AntD keeps inactive panes mounted (`aria-hidden` + `display: none`), so a bare page-level query
 * can match a hidden duplicate. Matching by ARIA role rather than AntD's internal class survives
 * rc-tabs DOM renames (`ant-tabs-tabpane-active` → `ant-tabs-content-active` in antd 6.5);
 * Playwright's role engine excludes aria-hidden elements, so this resolves to the visible pane only.
 */
export function activeTabPanel(scope: Page | Locator): Locator {
  return scope.getByRole('tabpanel');
}

/**
 * Activate an AntD tab reliably even when the tab bar has overflowed (#626 added a tenth tab to
 * the datasource settings page, which overflows a 1280px viewport).
 *
 * On overflow, AntD scrolls the tab list with a CSS `transform` and keeps out-of-view tabs in the
 * DOM. A physical Playwright click on a scrolled-out tab lands outside the visible strip and is
 * silently swallowed (Playwright cannot auto-scroll a transform-based container), so the tab never
 * activates and the spec times out on the panel content instead. Dispatching the click event fires
 * AntD's handler regardless of scroll position — the tab activates and AntD scrolls it into view.
 */
export async function clickTab(page: Page, name: RegExp | string): Promise<void> {
  await page.getByRole('tab', { name }).dispatchEvent('click');
}
