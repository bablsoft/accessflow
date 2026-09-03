import { type Page } from '@playwright/test';

/**
 * Open a collapsible sidebar sub-section so the links inside it become reachable.
 *
 * Sub-sections start collapsed — only the one holding the current route is forced open — so a
 * spec that reaches a page through its nav link opens the section first, exactly as a user does.
 * Section labels repeat across groups ("Database" sits under both Workflow and Connections), so
 * the lookup is scoped through the group heading. A no-op when the section is already open.
 */
export async function expandNavSection(
  page: Page,
  group: string,
  section: string,
): Promise<void> {
  const header = page
    .getByRole('group', { name: group })
    .getByRole('button', { name: `Expand ${section} section` });
  if ((await header.count()) > 0) {
    await header.click();
  }
}
