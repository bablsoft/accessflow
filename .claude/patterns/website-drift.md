# Website drift

**When to use:** Any edit under `website/`, or any app change touching the pitch, supported
databases, AI providers, auth methods, feature list, roadmap, quick-start commands, env vars, or
top-level URLs.
**Canonical example:** `website/sitemap.xml:5` (`<lastmod>`), `website/index.html:68` and `website/docs/index.html:65` (JSON-LD `dateModified`)
**Contract:** `frontend/src/config/docs.ts:19` (`DOCS_ANCHOR_PAGES`) ↔ `website/app.js:198` (`LEGACY_DOCS_ANCHORS`)
**Tests:** `frontend/src/config/__tests__/{docs,websitePages,websiteDocs,websiteCsp,websiteSecurityTxt}.test.ts`
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
    ├── guides/                 # task-oriented setup manuals: hub + 9 guides (AF-773)
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

`websitePages.test.ts` runs against **every** page on the site (not just `docs/`): byte-identical
nav — normalized for `aria-current` — and footer, self-referencing canonicals, `og:url` equal to
canonical, one `h1` and no skipped heading levels, no duplicate element ids, no dead fragment or
internal links, no parent-relative `href`, no `FAQPage`/`HowTo` structured data,
`<meta name="description">` within the SERP limit, the pinned legacy `/` section ids, bidirectional
`sitemap.xml` ↔ disk, a pinned `<priority>` per sitemap URL, every `llms.txt` URL resolving, both
halves on disk for every `<picture>` that declares a theme pair, and a ratcheting site-wide
inline-`style=""` budget.

`websiteDocs.test.ts` keeps only what is docs-specific: every chapter linked from every chapter
sidebar, and cross-chapter links resolving. Since AF-773 that sidebar carries three labelled
groups — **Documentation**, **Guides**, **Reference** — so a new page under `website/docs/` has to
be added to the right group in *every* docs page, not appended to one flat list.

**Freshness is now half-tested.** `websitePages.test.ts` asserts a page's three published
last-modified dates agree — visible `<time datetime>`, JSON-LD `dateModified`, `sitemap.xml`
`<lastmod>` — so moving one without the others fails CI. Nothing can verify the date is *today*;
that is still the half `.claude/hooks/website-drift.sh` warns on, and the half that silently rots.

## Required (acceptance checklist)

- [ ] **Bump all three copies of the modified date to today, together:** `<lastmod>` in
      `website/sitemap.xml`, `dateModified` in the page's JSON-LD, and — on a docs chapter — the
      visible `<p class="docs-updated"><time datetime>`. `websitePages.test.ts` fails when they
      disagree or one goes missing; **no test can tell you the date is stale**, only that the
      three agree, so bumping is still on you.
- [ ] New page → a new `<url>` block in `sitemap.xml`, **and a tier for it in
      `SITEMAP_PRIORITY`** (`websitePages.test.ts`). The gradient is pinned exactly, not as a
      range: `/` 1.0, top-level hubs 0.9, topic pages and the widely-read docs entry points 0.8
      (`/docs/install/`, `/docs/workflows/`, and the `/docs/guides/` sub-hub — a sub-hub sits at
      0.8, not 0.9, which is reserved for the three hubs a visitor starts a session on), the
      remaining chapters and every individual guide 0.7, `/roadmap/` 0.6. Flattening it is the
      SEO regression nothing on the page would show you.
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

- **Editing a page without bumping `lastmod`/`dateModified`** → crawlers keep the stale date. The
  three copies are tested against *each other*, never against today, so a synchronized-but-stale
  set sails through CI and the drift compounds silently across releases.
- **Adding `HowTo` schema** → deprecated in 2023.
- **Adding `FAQPage` schema** → Google retired FAQ rich results for all sites in May 2026. It is
  dead weight that can only hurt.
- **A description over 160 chars** → `websitePages.test.ts` fails, and Google rewrites your snippet.
- **Moving a doc section without touching `LEGACY_DOCS_ANCHORS`** → every already-deployed
  self-hosted frontend's *View docs* button 404s. Those forwarders are permanent, not transitional.
- **`href="../index.html"`** → a 307 on every click, and it splits link equity.
- **A `<picture>` shipped with only one twin on disk** → `websitePages.test.ts` fails. A figure
  is a theme pair when its `<source srcset>` and `<img src>` name *different* files, and
  `app.js`'s `swapDocsImages` only rewrites those; naming the same file in both marks a figure
  light-only, which renders light on a dark page. Capture writes both twins for every screen —
  ship both.
- **Assuming a build step exists** → there isn't one. What you write is what ships.

## Extending

`website/README.md` holds the content-source map: which part of the app or which `docs/` chapter
each section of the site is derived from. Update it when you add a section.

The `security.txt` expiry is guarded by `websiteSecurityTxt.test.ts`, which fails CI *before* the
file expires — if that test starts failing, the fix is to extend the expiry, not to skip the test.

Screenshots are regenerated with the Playwright capture script at `e2e/screenshots/capture.ts`
(run via `npx -y tsx`), not with browser preview tools; theme is selected through the
`af-preferences` localStorage key.
