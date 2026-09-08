import { test, expect, type Page, type Route } from '@playwright/test';
import { login } from '../helpers/login';

// AF-906, extended by AF-919. The e2e stack has no reachable AI provider, so the help agent can
// never be enabled for real here — availability, the session and the turn are stubbed at the network
// boundary and the spec asserts the panel's own contract: the launcher hides when the agent is off,
// an answer's markdown renders as formatted text through a closed subset that has no anchor or
// image case at all, and links come only from the server-resolved `citations` array.
const SESSION = {
  id: '5f0b2a5e-6c0a-4a1e-9a3f-9c4a2d7f1b20',
  title: '',
  message_count: 0,
  created_at: '2026-09-08T10:00:00Z',
};

// A real anchor from frontend/src/config/docs.ts — config/__tests__/docs.test.ts checks every
// accessflow.io/docs literal in e2e/ against DOCS_ANCHOR_PAGES.
const CITATION_URL = 'https://accessflow.io/docs/configuration/review-workflows/#cfg-review-plans';
/** A URL in the *answer text*: it must stay text, never become an anchor. */
const PHISH_URL = 'https://not-a-real-docs-site.example.com/reset';
/** An image beacon in the answer: it must render nothing, so no request leaves on render. */
const BEACON_URL = 'https://not-a-real-docs-site.example.com/beacon.png';

// Markdown the panel must render as formatting, mixed with every construct it must refuse: a
// markdown link, an image, raw HTML, a bare URL and a link-reference definition.
const ANSWER = [
  '## Submitting a query',
  '',
  'Open the **query editor**, pick a datasource and press `Submit`. [1]',
  '',
  '- Pick a datasource',
  '- Write the query',
  '',
  '```bash',
  'docker compose up -d',
  '```',
  '',
  `See also ${PHISH_URL} and [reset it](${PHISH_URL}) and <a href="${PHISH_URL}">this</a>.`,
  `![beacon](${BEACON_URL})`,
  '',
  `[1]: ${PHISH_URL}`,
].join('\n');

interface StubMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  citations: unknown[];
  corpus_version?: string;
  latency_ms?: number;
  created_at: string;
}

/**
 * Stands in for the server, statefully: `POST /messages` stores the turn and the subsequent
 * `GET /sessions/:id` replays it. That matters — the panel invalidates the conversation after
 * every ask, so a stub that always answered with an empty transcript would erase the answer it
 * had just returned, and the spec would be asserting against a server that cannot exist.
 */
async function stubHelpChat(page: Page, opts: { enabled: boolean }): Promise<void> {
  const stored: StubMessage[] = [];
  await page.route('**/api/v1/help-chat/availability', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        enabled: opts.enabled,
        retrieval_active: true,
        corpus_version: 'c0ac599ef7fc',
        chunk_count: 512,
      }),
    });
  });

  await page.route('**/api/v1/help-chat/sessions', async (route: Route) => {
    if (route.request().method() !== 'POST') {
      await route.continue();
      return;
    }
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify(SESSION),
    });
  });

  await page.route(`**/api/v1/help-chat/sessions/${SESSION.id}`, async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        session: { ...SESSION, message_count: stored.length },
        messages: stored,
      }),
    });
  });

  await page.route(
    `**/api/v1/help-chat/sessions/${SESSION.id}/messages`,
    async (route: Route) => {
      const body = route.request().postDataJSON() as { question: string; route_name?: string };
      const userMessage: StubMessage = {
        id: `a1b2c3d4-0000-4000-8000-${String(stored.length + 1).padStart(12, '0')}`,
        role: 'USER',
        content: body.question,
        citations: [],
        created_at: '2026-09-08T10:01:00Z',
      };
      const assistantMessage: StubMessage = {
        id: `a1b2c3d4-0000-4000-8000-${String(stored.length + 2).padStart(12, '0')}`,
        role: 'ASSISTANT',
        content: ANSWER,
        citations: [
          {
            index: 1,
            chunk_id: 'chunk-7',
            title: 'Review plans',
            section: 'Configuration',
            anchor: 'cfg-review-plans',
            url: CITATION_URL,
          },
        ],
        corpus_version: 'c0ac599ef7fc',
        latency_ms: 1840,
        created_at: '2026-09-08T10:01:02Z',
      };
      stored.push(userMessage, assistantMessage);
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          session: { ...SESSION, message_count: stored.length, title: body.question },
          user_message: userMessage,
          assistant_message: assistantMessage,
        }),
      });
    },
  );
}

/**
 * Opens the drawer and returns it. The launcher only mounts once `/help-chat/availability` has
 * answered, so clicking it blind races the response — wait for the button, then for the drawer.
 */
async function openHelpPanel(page: Page) {
  const launcher = page.getByRole('button', { name: 'Ask the help assistant' });
  await expect(launcher).toBeVisible({ timeout: 15_000 });
  await launcher.click();
  const panel = page.getByRole('dialog').filter({ hasText: 'Help assistant' });
  await expect(panel).toBeVisible();
  return panel;
}

test.describe('help chat panel (AF-906)', () => {
  test('1) launcher is hidden while the help agent is off', async ({ page }) => {
    await stubHelpChat(page, { enabled: false });
    await login(page);
    // Arm the wait before navigating: `goto` resolves on load, and the SPA's availability XHR can
    // finish either side of that, so registering afterwards can miss it and hang to timeout.
    const availability = page.waitForResponse((r) =>
      /\/api\/v1\/help-chat\/availability$/.test(r.url()),
    );
    await page.goto('/editor');
    await availability;

    await expect(
      page.getByRole('button', { name: 'Ask the help assistant' }),
    ).toHaveCount(0);
  });

  test('2) launcher opens the panel when the agent is on', async ({ page }) => {
    await stubHelpChat(page, { enabled: true });
    await login(page);
    await page.goto('/editor');

    const panel = await openHelpPanel(page);
    await expect(panel.getByLabel('Your question')).toBeVisible();
  });

  test('3) asking a question renders the answer, its citation chip, and no link from the text',
    async ({ page }) => {
      await stubHelpChat(page, { enabled: true });
      await login(page);
      await page.goto('/editor');

      const panel = await openHelpPanel(page);
      await panel.getByLabel('Your question').fill('How do I submit a query for review?');

      const askPromise = page.waitForResponse(
        (r) => r.request().method() === 'POST' && /\/messages$/.test(r.url()),
      );
      await panel.getByRole('button', { name: /Send/ }).click();
      const askResponse = await askPromise;
      expect(askResponse.status()).toBe(200);

      // The label sent with the question is the screen's name, never its path.
      const sent = askResponse.request().postDataJSON() as { route_name?: string };
      expect(sent.route_name).toBe('Query editor');

      const bubble = panel.locator('.af-help-bubble-rich');
      await expect(bubble).toBeVisible();

      // The supported subset renders as formatting rather than as syntax (AF-919).
      await expect(bubble.locator('h4')).toHaveText('Submitting a query');
      await expect(bubble.locator('strong')).toHaveText('query editor');
      await expect(bubble.locator('ul li')).toHaveCount(2);
      await expect(bubble.locator('pre code')).toHaveText('docker compose up -d');
      // The citation marker stays literal text beside its chip.
      await expect(bubble).toContainText('[1]');

      // The one link on the panel is the citation chip, resolved server-side.
      const links = panel.locator('a');
      await expect(links).toHaveCount(1);
      await expect(links.first()).toHaveAttribute('href', CITATION_URL);
      await expect(links.first()).toContainText('Review plans');

      // Nothing the model wrote became a link or an image: the bare URL stayed text, the markdown
      // link kept only its label, the raw HTML stayed escaped, and the beacon rendered nothing.
      await expect(panel.locator(`a[href="${PHISH_URL}"]`)).toHaveCount(0);
      await expect(bubble.locator('a')).toHaveCount(0);
      await expect(bubble.locator('img')).toHaveCount(0);
      await expect(bubble).toContainText(PHISH_URL);
      await expect(bubble).toContainText('reset it');
      await expect(bubble).toContainText('<a href=');
      await expect(bubble).not.toContainText(BEACON_URL);
    });
});
