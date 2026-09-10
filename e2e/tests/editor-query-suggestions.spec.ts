import { test, expect, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import {
  acceptInvitationViaApi,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  deleteReviewPlanViaApi,
  inviteUserViaApi,
  loginViaApi,
  recomputeQuerySuggestionsViaApi,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  waitForQuerySuggestions,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';
import { ADMIN_EMAIL, ADMIN_PASSWORD, login } from '../helpers/login';

const APPROVER_PASSWORD = 'E2ePassword!123';

// The shape the suggestion rail should end up offering. It is submitted and approved twice
// because accessflow.workflow.query-suggestions.min-approved-count defaults to 2 — a one-off
// query must not be able to fill the rail.
const SUGGESTED_SQL = 'SELECT id FROM af_suggestions_demo';

let datasource: CreatedDatasource | null = null;
let reviewPlan: CreatedReviewPlan | null = null;
let adminAccessToken = '';
let approverAccessToken = '';

async function openEditorOnDatasource(page: Page): Promise<void> {
  await page.goto('/editor');
  const dsSelect = page.getByRole('combobox').first();
  await dsSelect.click();
  // Register the schema wait BEFORE the option click — the fetch can complete before a
  // later-registered listener attaches.
  const schemaResponse = page.waitForResponse(
    (r) => r.url().includes(`/api/v1/datasources/${datasource!.id}/schema`),
    { timeout: 20_000 },
  );
  await page
    .locator('.ant-select-item-option')
    .filter({ hasText: datasource!.name })
    .click();
  await schemaResponse;
}

async function openSuggestionsRail(page: Page): Promise<void> {
  // AntD Segmented renders a visually-hidden radio input Playwright cannot click; click the
  // item label instead.
  await page
    .locator('.ant-segmented-item')
    .filter({ hasText: 'Suggestions' })
    .click();
}

test.describe.serial('automatic query suggestions in /editor (#776)', () => {
  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // A query can never be approved by its own submitter, so the corpus needs a second user
    // with reviewer authority. Fresh per run so re-runs do not trip the email uniqueness
    // constraint left behind by earlier invitation acceptances.
    const approverEmail = `sugg-approver-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverEmail,
      'AF-776 Approver',
      'ADMIN',
    );
    const inviteToken = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(
      request,
      inviteToken,
      APPROVER_PASSWORD,
      'AF-776 Approver',
    );
    approverAccessToken = await loginViaApi(
      request,
      approverEmail,
      APPROVER_PASSWORD,
    );

    // Without a review plan a datasource's pending queries are filtered out of the reviewer
    // queue and the decision is rejected as ineligible, so the approvals below would never land.
    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF776 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF776 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });

    for (let i = 0; i < 2; i++) {
      const submitted = await submitQueryViaApi(
        request,
        adminAccessToken,
        datasource.id,
        SUGGESTED_SQL,
        'e2e: seeding approved history for #776',
      );
      await waitForQueryStatus(
        request,
        adminAccessToken,
        submitted.id,
        'PENDING_REVIEW',
      );
      await approveQueryViaApi(request, approverAccessToken, submitted.id);
    }

    // The scheduled rebuild runs every six hours; ask for it now instead.
    await recomputeQuerySuggestionsViaApi(
      request,
      adminAccessToken,
      datasource.id,
    );
    await waitForQuerySuggestions(request, adminAccessToken, datasource.id, 1);
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
      datasource = null;
    }
    if (reviewPlan) {
      await deleteReviewPlanViaApi(request, adminAccessToken, reviewPlan.id);
      reviewPlan = null;
    }
  });

  test('the rail offers the approved query with its evidence', async ({ page }) => {
    await login(page);
    await openEditorOnDatasource(page);
    await openSuggestionsRail(page);

    await expect(page.getByText('Suggested queries')).toBeVisible();
    await expect(page.getByText(SUGGESTED_SQL)).toBeVisible();
    // Two approvals by one person — the evidence line reports both counts.
    await expect(page.getByText(/Approved 2× by 1/)).toBeVisible();
    // The rail must never read as pre-approval.
    await expect(
      page.getByText(/still analysed and reviewed like any other query/i),
    ).toBeVisible();
  });

  test('applying a suggestion fills the editor and still requires a submission', async ({
    page,
  }) => {
    await login(page);
    await openEditorOnDatasource(page);
    await openSuggestionsRail(page);

    await page.getByRole('button', { name: /Apply as draft/i }).click();

    await expect(page.locator('.cm-content')).toContainText(SUGGESTED_SQL);
    // Applying only drafts: the query is not submitted, so the page stays on /editor and the
    // Submit control is still there waiting for a decision by the analyst.
    await expect(page).toHaveURL(/\/editor$/);
    await expect(
      page.getByRole('button', { name: 'Submit for review' }),
    ).toBeVisible();
  });
});
