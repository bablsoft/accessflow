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

/**
 * Walk an AntD table's pagination until `row` is visible, and leave the table
 * on that page so follow-up interactions target the row directly.
 *
 * Under the parallel e2e project, org-shared paginated tables (users, review
 * plans, …) accumulate rows from every concurrently-running spec, so "my
 * freshly created row is on page 1" no longer holds. There is no server-side
 * search on these endpoints, so paging through is the reliable lookup.
 *
 * Throws if the row is not found on any page.
 */
export async function findRowAcrossPages(
  page: Page,
  row: Locator,
  {
    pageTimeoutMs = 2_000,
    totalTimeoutMs = 20_000,
  }: { pageTimeoutMs?: number; totalTimeoutMs?: number } = {},
): Promise<void> {
  const deadline = Date.now() + totalTimeoutMs;
  for (;;) {
    const found = await row
      .first()
      .waitFor({ state: 'visible', timeout: pageTimeoutMs })
      .then(() => true)
      .catch(() => false);
    if (found) return;
    if (Date.now() > deadline) {
      throw new Error(`Row not found on any table page within ${totalTimeoutMs}ms`);
    }
    // dispatchEvent rather than click: a table refresh overlays the pager
    // with an .ant-spin blur that intercepts pointer events, and a physical
    // click retries against it until the test times out (same trick as
    // clickTab above).
    const next = page.locator('.ant-pagination-next:not(.ant-pagination-disabled)');
    if (await next.count()) {
      await next.dispatchEvent('click');
    } else {
      // Sweep exhausted without a hit. The row may have landed on an earlier
      // page after we moved past it (fresh data still loading), so wrap back
      // to page 1 — or, with no pagination at all, just retry in place.
      const first = page.locator('.ant-pagination-item-1');
      if (await first.count()) await first.dispatchEvent('click');
    }
  }
}
