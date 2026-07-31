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
