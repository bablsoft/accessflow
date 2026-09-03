import { test, expect } from '@playwright/test';
import { login } from '../helpers/login';
import { expandNavSection } from '../helpers/nav';

// The sidebar's collapsible sub-sections (AF-837):
//   1. every section starts closed, so a fresh login shows group headings and nothing else
//   2. opening one reveals its links, and the choice survives a reload (`af-preferences`)
//   3. a deep link forces the section holding the current page open, and highlights exactly one
//      entry — `/request-groups/reviews` must not also light up its `/request-groups` sibling
test.describe('sidebar navigation', () => {
  test('every sub-section starts collapsed for a fresh session', async ({ page }) => {
    await login(page);

    const workflow = page.getByRole('group', { name: 'Workflow' });
    await expect(workflow.getByRole('button', { name: 'Expand Database section' }))
      .toHaveAttribute('aria-expanded', 'false');
    await expect(page.getByRole('link', { name: 'Query editor' })).toHaveCount(0);
    // The generic entries live outside any sub-section and stay reachable. Addressed by href:
    // AntD folds the icon's aria-label into the accessible name, and Playwright matches `name`
    // as a substring, so "Dashboard" would also pick up "dashboard Datasource health".
    await expect(page.locator('a.af-sidebar-item[href="/dashboard"]')).toBeVisible();
  });

  test('expanding a sub-section reveals its links and survives a reload', async ({ page }) => {
    await login(page);
    await expandNavSection(page, 'Workflow', 'Database');
    await expect(page.getByRole('link', { name: 'Query editor' })).toBeVisible();

    await page.reload();
    await expect(page.getByRole('link', { name: 'Query editor' })).toBeVisible();
  });

  test('a deep link opens the owning sub-section and highlights only that entry', async ({
    page,
  }) => {
    await login(page);
    await page.goto('/request-groups/reviews');
    await page.waitForURL('**/request-groups/reviews', { timeout: 15_000 });

    const workflow = page.getByRole('group', { name: 'Workflow' });
    await expect(
      workflow.getByRole('button', {
        name: 'Request groups section — kept open because it contains the current page',
      }),
    ).toBeDisabled();

    await expect(page.getByRole('link', { name: 'Group Reviews' })).toHaveClass(/active/);
    await expect(page.getByRole('link', { name: 'Request Groups' })).not.toHaveClass(/active/);
  });
});
