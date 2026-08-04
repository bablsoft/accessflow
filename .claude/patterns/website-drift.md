# Website drift

**When to use:** Any edit under `website/`, or any app change touching the pitch, supported
databases, AI providers, auth methods, feature list, roadmap, quick-start commands, env vars, or
top-level URLs.
**Canonical example:** `website/sitemap.xml:5` (`<lastmod>`), `website/index.html:68` and `website/docs/index.html:65` (JSON-LD `dateModified`)
**Contract:** `frontend/src/config/docs.ts:19` (`DOCS_ANCHOR_PAGES`) ↔ `website/app.js:198` (`LEGACY_DOCS_ANCHORS`)
**Tests:** `frontend/src/config/__tests__/{docs,websiteDocs,websiteCsp,websiteSecurityTxt}.test.ts`
**Related:** `website/README.md` (the content-source map)

## Shape

The site is **static HTML/CSS/JS with no build step** — edits land directly in `.html`. It lives
under `website/`, and its docs are one page per chapter:

```
website/
├── index.html                  # the pitch, connector grid, feature list
├── sitemap.xml                 # one <url> block per page, each with <lastmod>
├── app.js                      # LEGACY_DOCS_ANCHORS — permanent forwarders
└── docs/
    ├── index.html              # hub
    ├── install/
    ├── configuration/{users-roles,datasources,connectors,review-workflows,ai,auth,notifications,audit-compliance}/
    ├── workflows/
    └── iac/
```

Two contracts bind these pages to the app:

1. **`frontend/src/config/docs.ts` → `DOCS_ANCHOR_PAGES`** maps every in-app *View docs* anchor to
   the chapter that owns it. `docs.test.ts` fails when they disagree.
2. **`website/app.js` → `LEGACY_DOCS_ANCHORS`** permanently forwards pre-split `/docs/#anchor`
   links that already-released self-hosted frontends still emit. Moving a section between
   chapters means updating **both**.

## What is already tested, and what isn't

`websiteDocs.test.ts` covers a lot — every chapter exists, byte-identical nav and footer,
self-referencing canonicals, one `h1` and no skipped heading levels, no duplicate element ids, no
dead fragment or cross-chapter links, `<meta name="description">` within the SERP limit, and every
chapter listed in `sitemap.xml`.

**It does not check freshness.** Nothing verifies that `<lastmod>` or JSON-LD `dateModified` were
bumped when you edited a page. That is the half `.claude/hooks/website-drift.sh` warns on, and the
half that silently rots.

## Required (acceptance checklist)

- [ ] **Bump `<lastmod>` in `website/sitemap.xml` and `dateModified` in the JSON-LD of every page
      you touched, to today's date.** No test catches this.
- [ ] New page → a new `<url>` block in `sitemap.xml`.
- [ ] Moving a section between chapters → update `DOCS_ANCHOR_PAGES` **and** the `id` in the target
      chapter **and** `LEGACY_DOCS_ANCHORS`.
- [ ] `<meta name="description">` ≤ **160 rendered characters** (Google truncates past that and
      substitutes its own snippet).
- [ ] Link the homepage as `/` — never `../index.html`, which costs a 307 redirect hop.
- [ ] App-level changes propagate: `website/index.html` for the pitch / supported DBs / AI
      providers / auth methods / features / roadmap / quick-start, and the matching
      `website/docs/` chapter for deployment, configuration entities, the RBAC matrix, and
      operator-facing env vars.
- [ ] `README.md` updated in the same commit set when the change alters the pitch, tech-stack
      versions, quick-start commands, project structure, or top-level features.
- [ ] `cd frontend && npm run test:coverage` green — the website guards run in the frontend job.

## Anti-patterns

- **Editing a page without bumping `lastmod`/`dateModified`** → crawlers keep the stale date, and
  because nothing tests it the drift compounds silently across releases.
- **Adding `HowTo` schema** → deprecated in 2023.
- **Adding `FAQPage` schema** → Google retired FAQ rich results for all sites in May 2026. It is
  dead weight that can only hurt.
- **A description over 160 chars** → `websiteDocs.test.ts` fails, and Google rewrites your snippet.
- **Moving a doc section without touching `LEGACY_DOCS_ANCHORS`** → every already-deployed
  self-hosted frontend's *View docs* button 404s. Those forwarders are permanent, not transitional.
- **`href="../index.html"`** → a 307 on every click, and it splits link equity.
- **Assuming a build step exists** → there isn't one. What you write is what ships.

## Extending

`website/README.md` holds the content-source map: which part of the app or which `docs/` chapter
each section of the site is derived from. Update it when you add a section.

The `security.txt` expiry is guarded by `websiteSecurityTxt.test.ts`, which fails CI *before* the
file expires — if that test starts failing, the fix is to extend the expiry, not to skip the test.

Screenshots are regenerated with the Playwright capture script at `e2e/screenshots/capture.ts`
(run via `npx -y tsx`), not with browser preview tools; theme is selected through the
`af-preferences` localStorage key.
