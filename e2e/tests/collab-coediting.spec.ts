import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const COLLAB_PASSWORD = 'Collab-Pwd!123';
const COLLAB_NAME = 'AF-441 Collaborator';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

function editor(page: Page) {
  return page.getByTestId('collaborative-sql-editor').locator('.cm-content');
}

async function typeAtEnd(page: Page, text: string): Promise<void> {
  const content = editor(page);
  await content.click();
  await page.keyboard.press('Control+End');
  await page.keyboard.type(text, { delay: 25 });
  await page.keyboard.press('Escape');
}

// Provisioning the second collaborator via Mailcrab → invitation accept takes a
// couple of seconds; the two-context WS relay adds more. Give the suite headroom.
test.describe.configure({ timeout: 120_000 });

test.describe.serial('collaborative query editing (AF-441)', () => {
  let adminAccessToken = '';
  let collaboratorEmail = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // The collaborator is ADMIN so the seeded one-stage ADMIN review plan makes
    // them an eligible reviewer — and therefore an authorized co-author.
    collaboratorEmail = `af441-collab-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminAccessToken, collaboratorEmail, COLLAB_NAME, 'ADMIN');
    const token = await waitForInviteToken(request, collaboratorEmail);
    await acceptInvitationViaApi(request, token, COLLAB_PASSWORD, COLLAB_NAME);

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF441 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF441 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('two authorized users co-edit a query in review and exchange a comment', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 1',
      'AF-441 collaborative editing',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');

    const submitterCtx = await browser.newContext();
    const collaboratorCtx = await browser.newContext();
    try {
      const submitterPage = await submitterCtx.newPage();
      const collaboratorPage = await collaboratorCtx.newPage();

      // Submitter opens first → seeds the shared document from the query's SQL.
      await loginViaUi(submitterPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      await submitterPage.goto(`/queries/${submitted.id}`);
      await expect(submitterPage.getByText('SQL (collaborative)')).toBeVisible({ timeout: 15_000 });
      await expect(editor(submitterPage)).toContainText('SELECT 1', { timeout: 15_000 });

      // Collaborator joins → syncs the document state from the submitter.
      await loginViaUi(collaboratorPage, collaboratorEmail, COLLAB_PASSWORD);
      await collaboratorPage.goto(`/queries/${submitted.id}`);
      await expect(editor(collaboratorPage)).toContainText('SELECT 1', { timeout: 15_000 });

      // Presence: the submitter sees the collaborator's avatar (aria-label = display name).
      await expect(
        submitterPage.locator(`[aria-label="${COLLAB_NAME}"]`).first(),
      ).toBeVisible({ timeout: 15_000 });

      // Conflict-free co-editing: the submitter's keystrokes reach the collaborator.
      await typeAtEnd(submitterPage, ' /* coediting-token */');
      await expect(editor(collaboratorPage)).toContainText('coediting-token', { timeout: 15_000 });

      // Inline comment: the collaborator opens a thread; the submitter sees it live.
      await collaboratorPage
        .getByLabel('Add a comment…')
        .fill('needs an index on this column');
      await collaboratorPage.getByRole('button', { name: 'Comment' }).click();
      await expect(
        submitterPage.getByText('needs an index on this column'),
      ).toBeVisible({ timeout: 15_000 });

      // Resolve the thread, and confirm it persists across a reload.
      await collaboratorPage.getByRole('button', { name: /Resolve/ }).click();
      await expect(collaboratorPage.getByText('Resolved')).toBeVisible({ timeout: 10_000 });
      await collaboratorPage.reload();
      await expect(
        collaboratorPage.getByText('needs an index on this column'),
      ).toBeVisible({ timeout: 15_000 });
      await expect(collaboratorPage.getByText('Resolved')).toBeVisible({ timeout: 10_000 });
    } finally {
      await submitterCtx.close();
      await collaboratorCtx.close();
    }
  });
});
