import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  activeNavHrefs,
  digest,
  expectedCanonical,
  htmlFiles,
  mainWordCount,
  normalizeNav,
  pageUrl,
  slice,
  stripNonProse,
  websiteRoot,
} from './helpers/websiteHtml';

/**
 * Site-wide guards for website/.
 *
 * Until AF-794 every one of these assertions was scoped to website/docs/**, so the
 * landing page and /ai-agents/ — the two pages that matter most — were unguarded,
 * and any page added outside docs/ would have been born unguarded too. These run
 * against every page on the site.
 *
 * The docs-only assertions that genuinely have no site-wide meaning (the chapter
 * sidebar, cross-chapter link resolution) stay in websiteDocs.test.ts.
 */
const files = htmlFiles(websiteRoot);
const read = (f: string) => readFileSync(f, 'utf8');
const rel = (f: string) => path.relative(websiteRoot, f);

/** Paths that are assets, not pages — never expected to resolve to an index.html. */
const isAsset = (url: string) => /\.[a-z0-9]+$/.test(url);

const idsOf = (html: string) => new Set([...html.matchAll(/id="([a-z0-9-]+)"/g)].map((m) => m[1]!));

/** One node of a page's JSON-LD `@graph`. Only the keys the guards read are named. */
type GraphNode = {
  '@type': string;
  '@id'?: string;
  breadcrumb?: { '@id': string };
  itemListElement?: { name: string; item: string }[];
} & Record<string, unknown>;

/** The `@graph` of the single JSON-LD block on a page. */
const graphOf = (html: string, label: string): GraphNode[] => {
  const block = html.match(/<script type="application\/ld\+json">\s*([\s\S]*?)\s*<\/script>/);
  expect(block, `${label} has no JSON-LD block`).not.toBeNull();
  return JSON.parse(block![1]!)['@graph'] as GraphNode[];
};

/**
 * Inline style="" attributes left on the site. style-src cannot tighten from
 * 'unsafe-inline' to 'self' until this reaches 0 (website/_headers). Ratchet only
 * downwards — never raise this number to make a new page pass.
 */
const INLINE_STYLE_BUDGET = 36;

/**
 * AF-789 landing composition: index.html is a hub of teasers, not the
 * encyclopedia — the full content lives on the spoke pages. Measured at 1,104
 * words when the cut landed; the budget is a ceiling, not a target. Raising it
 * past 1300 needs the same scrutiny as raising the inline-style budget — and
 * aria-hidden is NOT a budget valve: it exists to exclude decorative mock-UI
 * facsimiles, never to hide real copy from the count.
 */
const MAIN_WORD_BUDGET = 1300;

/**
 * The 8 pages epic AF-782 carved out of the landing page. Hub-and-spoke only
 * works with no orphans: each must stay reachable from the homepage *body* —
 * the nav does not count (it is chrome, and AF-791 retargets it separately).
 * Exactly once, per the AF-789 acceptance: a second in-body link dilutes the
 * exact-match anchor text, so adding one must be a deliberate test change.
 */
const SPOKE_URLS = [
  '/features/',
  '/features/database-access-governance/',
  '/features/api-access-governance/',
  '/features/deployment-governance/',
  '/connectors/',
  '/use-cases/',
  '/security/',
  '/roadmap/',
];

/**
 * Sitemap <priority>, one entry per URL. AF-782 called this "the only priority
 * signal there is": 22 identical values tell a crawler nothing about which page
 * matters, and flattening is the easy accident because nothing renders it. Pinned
 * exactly, like INLINE_STYLE_BUDGET above — a page added later has to pick a tier
 * on purpose instead of inheriting one.
 *
 * The tiers: the landing page alone at 1.0; the three hubs a visitor starts from
 * (/features/, /ai-agents/, /docs/) at 0.9; the six topic pages plus /docs/install/
 * and /docs/workflows/, the two chapters every reader opens, at 0.8; the remaining
 * ten chapters — the eight under /docs/configuration/, /docs/iac/ and /docs/integrations/ — at 0.7,
 * each answering a question only some deployments ask; /roadmap/ at 0.6, since it
 * reports status rather than competing for a query.
 *
 * AF-773 added the /docs/guides/ section: the hub at 0.8, because it is an entry
 * point a visitor lands on and browses rather than a leaf they arrive at; each
 * individual guide at 0.7, alongside the reference chapters — a guide answers one
 * task, which is the same size of question a chapter answers.
 *
 * The /compare/ hub sits at 0.8 with the other topic pages a visitor navigates to;
 * its four comparison pages at 0.7, since each answers one question the way a guide
 * or a connector page does.
 */
/**
 * The competitor comparison pages. They are trust pages, not ads: every claim about
 * another product is sourced and dated, unverifiable cells say "Not documented" rather
 * than "No", and each page says where the other product is the better fit. The
 * guards below pin those editorial rules, because nothing on the page would show a
 * regression in them.
 */
const COMPARE_HUB = '/compare/';
const COMPARE_LEAVES = [
  '/compare/accessflow-vs-bytebase/',
  '/compare/accessflow-vs-hoop-dev/',
  '/compare/open-source-strongdm-alternative/',
  '/compare/open-source-teleport-database-access-alternative/',
];

const SITEMAP_PRIORITY = {
  '/': '1.0',
  '/features/': '0.9',
  '/ai-agents/': '0.9',
  '/docs/': '0.9',
  '/features/database-access-governance/': '0.8',
  '/features/api-access-governance/': '0.8',
  '/features/deployment-governance/': '0.8',
  '/security/': '0.8',
  '/connectors/': '0.8',
  '/use-cases/': '0.8',
  '/docs/install/': '0.8',
  '/docs/workflows/': '0.8',
  '/docs/guides/': '0.8',
  '/docs/guides/first-query/': '0.7',
  '/docs/guides/datasource/': '0.7',
  '/docs/guides/notifications/': '0.7',
  '/docs/guides/team/': '0.7',
  '/docs/guides/sso/': '0.7',
  '/docs/guides/ai-analysis/': '0.7',
  '/docs/guides/api-governance/': '0.7',
  '/docs/guides/deployment-approval/': '0.7',
  '/docs/guides/terraform/': '0.7',
  '/docs/guides/help-assistant/': '0.7',
  '/docs/configuration/users-roles/': '0.7',
  '/docs/configuration/datasources/': '0.7',
  '/docs/configuration/connectors/': '0.7',
  '/docs/configuration/review-workflows/': '0.7',
  '/docs/configuration/ai/': '0.7',
  '/docs/configuration/auth/': '0.7',
  '/docs/configuration/notifications/': '0.7',
  '/docs/configuration/audit-compliance/': '0.7',
  '/docs/iac/': '0.7',
  '/docs/integrations/': '0.7',
  '/roadmap/': '0.6',
  '/changelog/': '0.6',
  '/connectors/postgresql/': '0.7',
  '/connectors/mysql/': '0.7',
  '/connectors/mariadb/': '0.7',
  '/connectors/oracle/': '0.7',
  '/connectors/mssql/': '0.7',
  '/connectors/clickhouse/': '0.7',
  '/connectors/snowflake/': '0.7',
  '/connectors/bigquery/': '0.7',
  '/connectors/databricks/': '0.7',
  '/connectors/mongodb/': '0.7',
  '/connectors/couchbase/': '0.7',
  '/connectors/redis/': '0.7',
  '/connectors/dynamodb/': '0.7',
  '/connectors/cassandra/': '0.7',
  '/connectors/scylladb/': '0.7',
  '/connectors/elasticsearch/': '0.7',
  '/connectors/opensearch/': '0.7',
  '/connectors/neo4j/': '0.7',
  '/compare/': '0.8',
  '/compare/accessflow-vs-bytebase/': '0.7',
  '/compare/accessflow-vs-hoop-dev/': '0.7',
  '/compare/open-source-strongdm-alternative/': '0.7',
  '/compare/open-source-teleport-database-access-alternative/': '0.7',
} as const satisfies Record<string, string>;

/**
 * The header nav AF-791 swapped in: six real page links, replacing seven homepage
 * fragments plus /docs/. AF-782 rejected a dropdown deliberately — it would need new
 * app.js (click-outside, Escape, roving focus, aria-expanded) shipped to every page on
 * a site whose CSP forbids inline script, and a crawler cannot follow one. `/` and
 * `/ai-agents/` are reachable from the logo and the footer, so neither is a nav item.
 *
 * Byte-identity below only proves the pages agree with each other; this pins *what*
 * they agree on, so a revert to `/#features`-style anchors fails loudly rather than
 * passing 22 times over.
 */
const NAV_LINKS = [
  ['/features/', 'Features'],
  ['/connectors/', 'Connectors'],
  ['/use-cases/', 'Use cases'],
  ['/security/', 'Security'],
  ['/roadmap/', 'Roadmap'],
  ['/docs/', 'Docs'],
] as const satisfies readonly (readonly [href: string, label: string])[];

/** The nav item a page sits under: the longest nav href that prefixes its URL. */
const navSection = (url: string): string | undefined =>
  NAV_LINKS.map(([href]) => href)
    .filter((href) => url.startsWith(href))
    .sort((a, b) => b.length - a.length)[0];

describe('website pages', () => {
  it('finds every page on the site', () => {
    expect(files.length).toBeGreaterThanOrEqual(22);
    expect(files.map(rel)).toContain('index.html');
    expect(files.map(rel)).toContain(path.join('ai-agents', 'index.html'));
    expect(files.map(rel)).toContain(path.join('security', 'index.html'));
    expect(files.map(rel)).toContain(path.join('connectors', 'index.html'));
    expect(files.map(rel)).toContain(path.join('use-cases', 'index.html'));
    expect(files.map(rel)).toContain(path.join('roadmap', 'index.html'));
    expect(files.map(rel)).toContain(path.join('features', 'index.html'));
    expect(files.map(rel)).toContain(path.join('features', 'database-access-governance', 'index.html'));
    expect(files.map(rel)).toContain(path.join('features', 'api-access-governance', 'index.html'));
    expect(files.map(rel)).toContain(path.join('features', 'deployment-governance', 'index.html'));
    expect(files.map(rel)).toContain(path.join('compare', 'index.html'));
  });

  it('shares a byte-identical nav across every page', () => {
    const groups = new Map<string, string[]>();
    for (const f of files) {
      const nav = slice(read(f), '<header class="nav">', '<main');
      expect(nav, `${rel(f)} has no site nav`).not.toBe('');
      const k = digest(normalizeNav(nav));
      groups.set(k, [...(groups.get(k) ?? []), rel(f)]);
    }
    // More than one group means someone edited the nav in some files but not all.
    expect([...groups.values()]).toHaveLength(1);
  });

  it('gives every page the same six page links, in both the desktop and mobile nav', () => {
    for (const f of files) {
      const html = read(f);
      for (const [block, marker, end] of [
        ['desktop', '<nav class="nav-links"', '</nav>'],
        // Only the region above the divider: below it the panel carries the Quick start
        // entry and the theme toggle, which are controls rather than section links.
        ['mobile', '<nav class="nav-mobile-panel"', '<div class="nav-mobile-divider"'],
      ] as const) {
        const nav = slice(html, marker, end);
        // Count the anchor openings separately: the capture below anchors on `<a href="`,
        // so a link written attribute-first would be absent from `links` rather than wrong,
        // and would slip past a contents-only comparison on all 22 pages at once.
        expect([...nav.matchAll(/<a[\s>]/g)], `${rel(f)} ${block} nav link count`).toHaveLength(
          NAV_LINKS.length,
        );
        const links = [...nav.matchAll(/<a href="([^"]+)"[^>]*>([^<]+)<\/a>/g)].map((m) => [
          m[1]!,
          m[2]!,
        ]);
        expect(links, `${rel(f)} ${block} nav`).toEqual(NAV_LINKS.map(([h, l]) => [h, l]));
      }
    }
  });

  it('keeps a Quick start entry inside the mobile panel', () => {
    // The collapse ladder in styles.css hides .nav-right's ghost CTA below 1280px, the
    // GitHub chip below 1140px and the primary CTA below 520px, so on a phone this panel
    // IS the header nav. AF-791 took `Install` out of the six section links, which would
    // have left no route to /#install in the header at all on the width where the install
    // command matters most. It sits below the divider, with the theme toggle.
    for (const f of files) {
      const panel = slice(read(f), '<div class="nav-mobile-divider"', '</nav>');
      expect(panel, `${rel(f)} mobile panel lost its Quick start entry`).toContain(
        '<a href="/#install">Quick start</a>',
      );
    }
  });

  it('marks the nav link for the section it is in, and only that one', () => {
    // normalizeNav strips these markers before hashing so pages in different sections
    // compare equal — which would otherwise retire the check entirely. Both the desktop
    // nav and the mobile panel carry the marker, hence two hits.
    for (const f of files) {
      const nav = slice(read(f), '<header class="nav">', '<main');
      const section = navSection(pageUrl(f));
      // `/` and `/ai-agents/` are not nav items, so they mark nothing.
      const expected = section ? [section, section] : [];
      expect(activeNavHrefs(nav), `${rel(f)} marks the wrong nav link active`).toEqual(expected);
    }
  });

  it('shares a byte-identical footer across every page', () => {
    const groups = new Map<string, string[]>();
    for (const f of files) {
      const foot = slice(read(f), '<footer', '</body>');
      expect(foot, `${rel(f)} has no footer`).not.toBe('');
      const k = digest(foot);
      groups.set(k, [...(groups.get(k) ?? []), rel(f)]);
    }
    expect([...groups.values()]).toHaveLength(1);
  });

  it('gives each page a self-referencing canonical matching its path', () => {
    for (const f of files) {
      expect(read(f), rel(f)).toContain(`<link rel="canonical" href="${expectedCanonical(f)}" />`);
    }
  });

  it('gives each page an og:url equal to its canonical, plus og:title and twitter:card', () => {
    for (const f of files) {
      const html = read(f);
      const ogUrl = html.match(/<meta property="og:url" content="([^"]*)"/);
      expect(ogUrl, `${rel(f)} has no og:url`).not.toBeNull();
      expect(ogUrl![1], `${rel(f)} og:url`).toBe(expectedCanonical(f));
      expect(html, `${rel(f)} has no og:title`).toMatch(/<meta property="og:title" content="[^"]+"/);
      expect(html, `${rel(f)} has no twitter:card`).toMatch(
        /<meta name="twitter:card" content="[^"]+"/,
      );
    }
  });

  it('gives each page exactly one h1 and no skipped heading levels', () => {
    for (const f of files) {
      const main = slice(read(f), '<main', '</main>');
      const levels = [...main.matchAll(/<h([1-6])[^>]*>/g)].map((m) => Number(m[1]));
      expect(levels.filter((l) => l === 1), `${rel(f)} h1 count`).toHaveLength(1);
      const skips = levels.slice(1).filter((l, i) => l > levels[i]! + 1);
      expect(skips, `${rel(f)} skips a heading level`).toEqual([]);
    }
  });

  it('keeps every description within the SERP snippet limit', () => {
    for (const f of files) {
      const m = read(f).match(/<meta name="description" content="([\s\S]*?)" \/>/);
      expect(m, `${rel(f)} has no meta description`).not.toBeNull();
      const rendered = m![1]!.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&lt;/g, '<');
      expect(rendered.length, `${rel(f)} description is ${rendered.length} chars`).toBeLessThanOrEqual(160);
    }
  });

  it('declares no duplicate element ids within a page', () => {
    for (const f of files) {
      const ids = [...read(f).matchAll(/id="([a-z0-9-]+)"/g)].map((m) => m[1]!);
      const dupes = [...new Set(ids.filter((id, i) => ids.indexOf(id) !== i))];
      expect(dupes, `${rel(f)} has duplicate ids`).toEqual([]);
    }
  });

  it('has no dead same-page fragment links', () => {
    for (const f of files) {
      const html = read(f);
      const ids = idsOf(html);
      const main = slice(html, '<main', '</main>');
      const dead = [...main.matchAll(/href="#([a-z0-9-]+)"/g)]
        .map((m) => m[1]!)
        .filter((frag) => !ids.has(frag));
      expect([...new Set(dead)], `${rel(f)} links to missing anchors`).toEqual([]);
    }
  });

  it('resolves every internal anchor href to a page and anchor that exist', () => {
    const idsByUrl = new Map(files.map((f) => [pageUrl(f), idsOf(read(f))]));
    for (const f of files) {
      const dead: string[] = [];
      // Anchors only — <link rel="preload"> and friends point at assets, not pages.
      for (const a of read(f).matchAll(/<a\s[^>]*href="(\/[^"#]*)(?:#([a-z0-9-]+))?"/g)) {
        const url = a[1]!;
        const frag = a[2];
        if (isAsset(url)) continue; // covered by the referenced-asset test below
        const ids = idsByUrl.get(url);
        if (!ids) dead.push(`${url} (no such page)`);
        else if (frag && !ids.has(frag)) dead.push(`${url}#${frag} (no such id)`);
      }
      expect([...new Set(dead)], `${rel(f)} has dead internal anchor links`).toEqual([]);
    }
  });

  it('ships every asset the pages reference', () => {
    for (const f of files) {
      const missing = new Set<string>();
      const html = read(f);
      // src/srcset/href pointing at a root-absolute file, plus the absolute og:image URL.
      for (const m of html.matchAll(/(?:src|srcset|href)="(\/[^"\s]+\.[a-z0-9]{2,5})"/g)) {
        if (!existsSync(path.join(websiteRoot, m[1]!))) missing.add(m[1]!);
      }
      for (const m of html.matchAll(/content="https:\/\/accessflow\.io(\/[^"\s]+\.[a-z0-9]{2,5})"/g)) {
        if (!existsSync(path.join(websiteRoot, m[1]!))) missing.add(m[1]!);
      }
      expect([...missing], `${rel(f)} references missing assets`).toEqual([]);
    }
  });

  it('writes every anchor href in a form the link guards can resolve', () => {
    // A bare relative href like "docs/" would silently escape the resolution check
    // above, which only matches href="/…". Locks in the AF-793 normalization.
    for (const f of files) {
      const odd = [...read(f).matchAll(/<a\s[^>]*href="([^"]*)"/g)]
        .map((m) => m[1]!)
        .filter((h) => !/^(?:\/|#|https?:\/\/|mailto:)/.test(h));
      expect([...new Set(odd)], `${rel(f)} has hrefs the guards cannot resolve`).toEqual([]);
    }
  });

  it('links the homepage as / and never as a relative parent path', () => {
    // href="../index.html" costs a 307 redirect hop and splits link equity.
    for (const f of files) {
      const up = [...new Set([...read(f).matchAll(/href="(\.\.\/[^"]*)"/g)].map((m) => m[1]!))];
      expect(up, `${rel(f)} uses parent-relative hrefs`).toEqual([]);
    }
  });

  it('declares no FAQPage or HowTo structured data', () => {
    // HowTo was deprecated in 2023; Google retired FAQ rich results for all sites
    // in May 2026. Both are dead weight that can only hurt.
    for (const f of files) {
      for (const block of read(f).matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)) {
        expect(block[1]!, `${rel(f)} declares FAQPage`).not.toContain('FAQPage');
        expect(block[1]!, `${rel(f)} declares HowTo`).not.toContain('HowTo');
      }
    }
  });

  it('keeps the legacy homepage section ids alive', () => {
    // Fragments never reach the server, so no redirect can ever repair these.
    // AF-791 took five of the seven out of the header nav, which does NOT retire them:
    // they are still in the footer (#how, #install, #questions), in llms.txt, in the nav
    // of every already-deployed page, and in the wild.
    const html = read(path.join(websiteRoot, 'index.html'));
    const missing = ['features', 'connectors', 'how', 'use-cases', 'install', 'questions', 'roadmap']
      .filter((id) => !idsOf(html).has(id));
    expect(missing, 'index.html dropped a legacy section id').toEqual([]);
  });

  it('keeps the landing page within its word budget', () => {
    const words = mainWordCount(read(path.join(websiteRoot, 'index.html')));
    expect(words, 'index.html <main> prose word count').toBeLessThanOrEqual(MAIN_WORD_BUDGET);
  });

  it('links every AF-782 spoke page from the landing page body, exactly once', () => {
    // stripNonProse so a commented-out teaser or a script-embedded string can
    // neither satisfy the link requirement nor trip the exactly-once half.
    const main = stripNonProse(slice(read(path.join(websiteRoot, 'index.html')), '<main', '</main>'));
    const counts = SPOKE_URLS.map((u) => `${u} ×${main.split(`href="${u}"`).length - 1}`);
    expect(counts, 'spoke links from index.html <main>').toEqual(SPOKE_URLS.map((u) => `${u} ×1`));
  });

  describe('mainWordCount', () => {
    // The word-budget guard is only as strong as this tokenizer, so its
    // semantics are pinned on fixtures rather than only on live content.
    it('counts prose words and ignores separator glyphs', () => {
      expect(mainWordCount('<main><p>two words · → — ✓ 3</p></main>')).toBe(3);
    });
    it('decodes entities without inflating the count', () => {
      expect(mainWordCount('<main><p>Q&amp;A one&nbsp;two &rarr; &#x2192; &#8594;</p></main>')).toBe(3);
    });
    it('excludes aria-hidden subtrees, including nested ones', () => {
      expect(
        mainWordCount(
          '<main><p>kept</p><div aria-hidden="true"><div><p>hidden words</p></div></div><p>also kept</p></main>',
        ),
      ).toBe(3);
    });
    it('survives void and self-closing tags inside a hidden subtree', () => {
      expect(
        mainWordCount('<main><div aria-hidden="true">hidden<br /><img src="x.png" />gone</div>kept</main>'),
      ).toBe(1);
    });
    it('excludes comments, scripts and svg', () => {
      expect(
        mainWordCount('<main><!-- ghost --><script>var x = "ghost";</script><svg><text>ghost</text></svg>kept</main>'),
      ).toBe(1);
    });
  });

  it('leaves no page reachable from only a handful of others', () => {
    // The three /features/ spokes are the longest commercial pages on the site and
    // had 4 inbound links each: absent from the nav, the footer and every docs
    // sidebar, reachable only from prose on / and /features/ and from each other.
    // Nothing noticed, because every individual link was valid. A floor does.
    const MIN_INBOUND = 10;
    const urls = new Set(files.map(pageUrl));
    const inbound = new Map([...urls].map((u) => [u, new Set<string>()]));
    for (const f of files) {
      const from = pageUrl(f);
      for (const m of read(f).matchAll(/href="(\/[^"\s]*)"/g)) {
        const to = m[1]!.split('#')[0] || '/';
        if (urls.has(to) && to !== from) inbound.get(to)!.add(from);
      }
    }
    const starved = [...inbound.entries()]
      .filter(([, from]) => from.size < MIN_INBOUND)
      .map(([u, from]) => `${u} (${from.size})`);
    expect(starved.sort(), `pages with fewer than ${MIN_INBOUND} inbound pages`).toEqual([]);
  });

  it('serves a real 404 page that recovers the visitor instead of dead-ending', () => {
    // Cloudflare's default not_found_handling returns a correct 404 status with a
    // zero-byte body. wrangler.jsonc opts into 404-page; this pins the page it needs.
    const html = readFileSync(path.join(websiteRoot, '404.html'), 'utf8');
    expect(html, '404 must not be indexable').toMatch(
      /<meta name="robots" content="noindex, follow" \/>/,
    );
    expect(html, '404 must not claim a canonical URL').not.toContain('rel="canonical"');
    expect(html, '404 must not be advertised in the sitemap').not.toContain('404.html');
    // It has to be a way back in, not just a branded dead end.
    expect(slice(html, '<header class="nav">', '<main'), '404 has no site nav').not.toBe('');
    expect(slice(html, '<footer>', '</footer>'), '404 has no site footer').not.toBe('');
    for (const href of ['/', '/features/', '/connectors/', '/security/', '/docs/']) {
      expect(html, `404 does not link ${href}`).toContain(`href="${href}"`);
    }
    const sitemap = readFileSync(path.join(websiteRoot, 'sitemap.xml'), 'utf8');
    expect(sitemap).not.toContain('404');
    const wrangler = readFileSync(path.join(websiteRoot, 'wrangler.jsonc'), 'utf8');
    expect(wrangler, 'wrangler must opt into the 404 page').toContain('"not_found_handling": "404-page"');
  });

  it('keeps sitemap.xml and the pages on disk in sync both ways', () => {
    const sitemap = readFileSync(path.join(websiteRoot, 'sitemap.xml'), 'utf8');
    const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]!);
    const canonicals = files.map(expectedCanonical);
    expect(canonicals.filter((c) => !locs.includes(c)), 'pages missing from sitemap.xml').toEqual([]);
    expect(locs.filter((l) => !canonicals.includes(l)), 'sitemap.xml URLs with no page on disk').toEqual([]);
  });

  it('keeps every sitemap priority on its tier', () => {
    // Both directions in one assertion: a page whose <url> block lost its
    // <priority>, a new page nobody assigned a tier, and a wholesale flatten to
    // one value all surface as the same diff.
    const sitemap = readFileSync(path.join(websiteRoot, 'sitemap.xml'), 'utf8');
    const blocks = sitemap.split('<url>').slice(1);
    const declared = Object.fromEntries(
      blocks.map((block) => [
        block.match(/<loc>https:\/\/accessflow\.io([^<]*)<\/loc>/)?.[1] ?? '(no loc)',
        block.match(/<priority>([^<]+)<\/priority>/)?.[1] ?? '(no priority)',
      ]),
    );
    // fromEntries would swallow a URL listed twice, so count the blocks too.
    expect(Object.keys(declared), 'duplicate <loc> in sitemap.xml').toHaveLength(blocks.length);
    expect(declared, 'sitemap.xml <priority> values').toEqual(SITEMAP_PRIORITY);
  });

  it('links every page in llms.txt, homepage included', () => {
    // The reverse of the check below: llms.txt is what an AI crawler reads instead
    // of the site, so a page missing from it is invisible to that path. The homepage
    // was absent — it appeared only as the /#install and /#questions fragments.
    const llms = readFileSync(path.join(websiteRoot, 'llms.txt'), 'utf8');
    const linked = new Set(
      [...llms.matchAll(/\]\((https:\/\/accessflow\.io[^)#\s]*)/g)].map((m) =>
        m[1]!.replace('https://accessflow.io', '') || '/',
      ),
    );
    const missing = files.map(pageUrl).filter((u) => !linked.has(u));
    expect(missing.sort(), 'pages absent from llms.txt').toEqual([]);
  });

  it('points every llms.txt URL at something that exists', () => {
    const llms = readFileSync(path.join(websiteRoot, 'llms.txt'), 'utf8');
    const idsByUrl = new Map(files.map((f) => [pageUrl(f), idsOf(read(f))]));
    const dead: string[] = [];
    for (const m of llms.matchAll(/https:\/\/accessflow\.io(\/[^)\s]*)?/g)) {
      const raw = (m[1] ?? '/').replace(/[.,]$/, '');
      const [urlPart, frag] = raw.split('#');
      const url = urlPart || '/';
      if (isAsset(url)) {
        if (!existsSync(path.join(websiteRoot, url))) dead.push(`${url} (no such file)`);
        continue;
      }
      const ids = idsByUrl.get(url);
      if (!ids) dead.push(`${url} (no such page)`);
      else if (frag && !ids.has(frag)) dead.push(`${url}#${frag} (no such id)`);
    }
    expect([...new Set(dead)], 'llms.txt points at missing content').toEqual([]);
  });

  it('ships both halves of every <picture> that declares a theme pair', () => {
    // app.js swapDocsImages() rewrites -light <-> -dark at load, not just on click,
    // and the default theme is dark — so a figure that promises a pair it cannot
    // deliver is a 404 for most visitors, not a toggle-only glitch. hasBothThemeVariants()
    // skips a figure whose authored <source> and <img> name the SAME file — the
    // light-only escape hatch, which no figure uses since #798 made capture.ts write
    // both twins for every screen. Anything that names two different files is claiming
    // a pair, and both halves must exist.
    const missing = new Set<string>();
    for (const f of files) {
      for (const block of read(f).matchAll(/<picture>[\s\S]*?<\/picture>/g)) {
        const source = block[0].match(/<source[^>]*\ssrcset="([^"]+)"/)?.[1];
        const img = block[0].match(/<img[^>]*\ssrc="([^"]+)"/)?.[1];
        if (!source || !img || source === img) continue;
        for (const url of [source, img]) {
          if (!existsSync(path.join(websiteRoot, url))) missing.add(`${rel(f)} -> ${url}`);
        }
      }
    }
    expect([...missing].sort()).toEqual([]);
  });

  it('skips the theme swap for a light-only figure instead of inventing a -dark URL', () => {
    // The runtime guard is only sound while it reads the pairing off the authored
    // attributes: after one swap both can legitimately match, so it must be cached.
    const js = readFileSync(path.join(websiteRoot, 'app.js'), 'utf8');
    expect(js, 'swapDocsImages must consult the pairing guard').toMatch(
      /if \(!hasBothThemeVariants\(pic\)\) return;/,
    );
    expect(js, 'the pairing verdict must be cached on the element').toMatch(
      /setAttribute\('data-theme-pair'/,
    );
  });

  it('renders a visible breadcrumb that matches its BreadcrumbList exactly', () => {
    // BreadcrumbList was declared on all 21 non-home pages while no page rendered a
    // trail, so the markup described navigation that did not exist. The two are one
    // fact; drifting them apart is the failure this catches.
    for (const f of files) {
      const html = read(f);
      const graph = graphOf(html, rel(f));
      const crumbs = graph.find((n) => n['@type'] === 'BreadcrumbList');
      if (pageUrl(f) === '/') {
        expect(crumbs, 'the homepage needs no breadcrumb').toBeUndefined();
        expect(html, 'the homepage must not render one either').not.toContain('class="breadcrumb"');
        continue;
      }
      expect(crumbs, `${rel(f)} has no BreadcrumbList`).toBeDefined();
      const nav = html.match(/<nav class="breadcrumb"[\s\S]*?<\/nav>/)?.[0];
      expect(nav, `${rel(f)} declares a BreadcrumbList but renders no trail`).toBeDefined();

      const items = crumbs!.itemListElement!;
      const visible = [...nav!.matchAll(/<(?:a href="[^"]*"|span aria-current="page")>([^<]+)</g)]
        .map((m) => m[1]!.replace(/&amp;/g, '&'));
      expect(visible, `${rel(f)} visible trail`).toEqual(items.map((i) => i.name));
      // Only the last crumb is the current page; the rest must be real links up.
      const hrefs = [...nav!.matchAll(/<a href="([^"]*)"/g)].map((m) => m[1]!);
      expect(hrefs, `${rel(f)} crumb links`).toEqual(
        items.slice(0, -1).map((i) => i.item.replace('https://accessflow.io', '')),
      );
      expect(nav!.match(/aria-current="page"/g), `${rel(f)} one current crumb`).toHaveLength(1);

      // And the graph has to point at it, or the list floats unattached.
      const page = graph.find((n) =>
        ['TechArticle', 'CollectionPage', 'WebPage'].includes(n['@type']),
      );
      expect(page?.breadcrumb?.['@id'], `${rel(f)} page node breadcrumb`).toBe(crumbs!['@id']);
    }
  });

  it('resolves every JSON-LD @id reference inside its own document', () => {
    // Schema consumers parse per-document; cross-document @id merging is not
    // guaranteed. #software and #website used to be defined only on the homepage
    // and referenced as bare {"@id": …} stubs everywhere else, so on 21 of 22 pages
    // TechArticle.about and .isPartOf pointed at a typeless nothing.
    for (const f of files) {
      const graph = graphOf(read(f), rel(f));
      const defined = new Set(graph.filter((n) => n['@id'] && n['@type']).map((n) => n['@id']!));
      const refs = new Set<string>();
      const walk = (x: unknown): void => {
        if (Array.isArray(x)) x.forEach(walk);
        else if (typeof x === 'object' && x !== null) {
          const keys = Object.keys(x);
          if (keys.length === 1 && keys[0] === '@id') refs.add((x as { '@id': string })['@id']);
          Object.values(x).forEach(walk);
        }
      };
      walk(graph);
      expect([...refs].filter((r) => !defined.has(r)).sort(), `${rel(f)} dangling @id`).toEqual([]);
    }
  });

  it('anchors the organization entity on accessflow.io, not on GitHub', () => {
    // "AccessFlow" is also an Alcor IGA product and an accessiBe product, so the
    // publishing entity has to be bound to the domain being ranked. GitHub belongs
    // in sameAs, never in @id or url.
    for (const f of files) {
      const html = read(f);
      expect(html, `${rel(f)} still anchors the org on GitHub`).not.toContain(
        '"@id": "https://github.com/bablsoft#org"',
      );
      const org = graphOf(html, rel(f)).find((n) => n['@type'] === 'Organization');
      expect(org, `${rel(f)} has no Organization node`).toBeDefined();
      expect(org!['@id'], `${rel(f)} Organization @id`).toBe('https://accessflow.io/#org');
      expect(org!.url, `${rel(f)} Organization url`).toBe('https://accessflow.io/');
      // Google's logo guidelines accept raster only; an SVG is silently ineligible.
      const logo = org!.logo as string;
      expect(logo, `${rel(f)} Organization logo must be raster`).toMatch(/\.(png|jpg|gif)$/);
      expect(existsSync(path.join(websiteRoot, new URL(logo).pathname))).toBe(true);
      expect(org!.sameAs, `${rel(f)} keeps GitHub in sameAs`).toContain(
        'https://github.com/bablsoft',
      );
      // The domain also has history — until late 2024 it hosted an unrelated
      // "on-demand access management" site whose title some indexes still show.
      // Anchoring says where the entity lives; alternateName says which one it is.
      expect(org!.alternateName, `${rel(f)} Organization alternateName`).toBe(
        'bablsoft (maker of AccessFlow)',
      );
      const software = graphOf(html, rel(f)).find((n) => n['@type'] === 'SoftwareApplication');
      expect(software, `${rel(f)} has no SoftwareApplication node`).toBeDefined();
      expect(software!.alternateName, `${rel(f)} SoftwareApplication alternateName`).toEqual([
        'AccessFlow by bablsoft',
        'bablsoft AccessFlow',
      ]);
      expect(software!.sameAs, `${rel(f)} SoftwareApplication sameAs`).toContain(
        'https://github.com/bablsoft/accessflow',
      );
    }
  });

  it('gives every page a datePublished that is real, and not later than dateModified', () => {
    // dateModified had a three-way guard; datePublished had none, and drifted to a
    // 2026-04-01 placeholder on 13 pages — 29 days before the repo's first commit.
    // Floor is that first commit: nothing on this site can predate the project.
    const PROJECT_START = '2026-04-30';
    for (const f of files) {
      const html = read(f);
      const published = [...html.matchAll(/"datePublished":\s*"([0-9-]+)"/g)].map((m) => m[1]!);
      const modified = [...html.matchAll(/"dateModified":\s*"([0-9-]+)"/g)].map((m) => m[1]!);
      expect(published, `${rel(f)} has no JSON-LD datePublished`).not.toHaveLength(0);
      for (const d of published) {
        expect(d, `${rel(f)} datePublished is not an ISO date`).toMatch(/^\d{4}-\d{2}-\d{2}$/);
        expect(
          d >= PROJECT_START,
          `${rel(f)} datePublished ${d} predates the project (${PROJECT_START})`,
        ).toBe(true);
        for (const m of modified) {
          expect(d <= m, `${rel(f)} datePublished ${d} is after dateModified ${m}`).toBe(true);
        }
      }
    }
  });

  it('agrees on each page last-modified date across all three places it is published', () => {
    // Visible <time datetime>, JSON-LD dateModified and sitemap <lastmod> are three
    // copies of one fact. Nothing else checks they were moved together.
    const sitemap = readFileSync(path.join(websiteRoot, 'sitemap.xml'), 'utf8');
    const lastmod = new Map(
      [...sitemap.matchAll(/<loc>([^<]+)<\/loc>\s*<lastmod>([^<]+)<\/lastmod>/g)].map((m) => [m[1]!, m[2]!]),
    );
    for (const f of files) {
      const html = read(f);
      const dates = new Set<string>();
      const jsonLd = [...html.matchAll(/"dateModified":\s*"([0-9-]+)"/g)].map((m) => m[1]!);
      // Presence matters as much as agreement: deleting a copy must not read as
      // "they all agree" — which a set of the survivors alone would.
      expect(jsonLd, `${rel(f)} has no JSON-LD dateModified`).not.toHaveLength(0);
      for (const d of jsonLd) dates.add(d);
      const visible = html.match(/<p class="docs-updated">\s*Last updated\s*<time datetime="([0-9-]+)"/);
      if (pageUrl(f).startsWith('/docs/')) {
        expect(visible, `${rel(f)} has no visible "Last updated" time`).not.toBeNull();
      }
      if (visible) dates.add(visible[1]!);
      const declared = lastmod.get(expectedCanonical(f));
      expect(declared, `${rel(f)} has no sitemap <lastmod>`).toBeDefined();
      dates.add(declared!);
      expect([...dates], `${rel(f)} publishes disagreeing modified dates`).toHaveLength(1);
    }
  });

  it('ratchets the inline style attributes down', () => {
    const total = files.reduce((n, f) => n + [...read(f).matchAll(/style="/g)].length, 0);
    // Exact, not <=: removing an inline style must also lower the constant, which is
    // what forces website/_headers and website/README.md down in the same commit.
    expect(total, 'inline style="" attributes site-wide').toBe(INLINE_STYLE_BUDGET);
  });

  describe('comparison pages', () => {
    const compareFiles = files.filter((f) => pageUrl(f).startsWith(COMPARE_HUB));
    const fileOf = (url: string) => files.find((f) => pageUrl(f) === url)!;
    const decode = (s: string) =>
      s
        .replace(/&amp;/g, '&')
        .replace(/&quot;/g, '"')
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&(?:nbsp|ensp|emsp|thinsp);/g, ' ')
        .replace(/&[a-z]+;|&#x?[0-9a-f]+;/gi, ' ');
    const text = (fragment: string) => decode(stripNonProse(fragment).replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
    const section = (html: string, id: string) =>
      slice(html, `<section class="docs-section scroll-pad" id="${id}"`, '</section>');
    /** The page's own narrative: <main> minus the sidebar, every table, the disclaimer and the sources. */
    const narrative = (html: string) =>
      slice(html, '<main', '</main>')
        .replace(/<aside[\s\S]*?<\/aside>/g, ' ')
        .replace(/<table[\s\S]*?<\/table>/g, ' ')
        .replace(/<div class="docs-callout info" id="disclaimer">[\s\S]*?<\/div>/, ' ')
        .replace(section(html, 'sources'), ' ');

    it('consists of exactly the hub and the four comparison pages', () => {
      expect(compareFiles.map(pageUrl).sort()).toEqual([COMPARE_HUB, ...COMPARE_LEAVES].sort());
    });

    it('carries enough prose of its own — the table, sidebar and boilerplate do not count', () => {
      // 900 words per comparison, 400 for the hub: the quality gate for a page that has
      // to earn a query rather than restate a feature matrix.
      for (const f of compareFiles) {
        const words = mainWordCount(`<main>${narrative(read(f))}</main>`);
        const floor = pageUrl(f) === COMPARE_HUB ? 400 : 900;
        expect(words, `${rel(f)} narrative word count`).toBeGreaterThanOrEqual(floor);
      }
    });

    it('ends with a dated Sources section of nofollow links, dated the same day as the page', () => {
      for (const f of compareFiles) {
        const html = read(f);
        const src = section(html, 'sources');
        expect(src, `${rel(f)} has no #sources section`).not.toBe('');
        expect([...src.matchAll(/href="https:\/\//g)].length, `${rel(f)} sources link count`).toBeGreaterThan(0);
        const external = [...src.matchAll(/<a\s[^>]*href="https?:\/\/[^"]*"[^>]*>/g)]
          .map((m) => m[0])
          .filter((a) => !a.includes('https://github.com/bablsoft/'));
        for (const a of external) {
          expect(a, `${rel(f)} competitor link must be nofollow noopener`).toContain('rel="nofollow noopener"');
        }
        const checked = [...src.matchAll(/<time datetime="([0-9-]+)"/g)].map((m) => m[1]!);
        expect(checked, `${rel(f)} sources checked date`).toHaveLength(1);
        const modified = html.match(/"dateModified":\s*"([0-9-]+)"/)![1];
        // Re-checking the sources is a content change, so it moves the page's date too.
        expect(checked[0], `${rel(f)} sources date vs dateModified`).toBe(modified);
      }
    });

    it('says where the other product is the better fit, with concrete cases', () => {
      for (const url of COMPARE_LEAVES) {
        const html = read(fileOf(url));
        for (const id of ['better-fit', 'accessflow-fit']) {
          const s = section(html, id);
          expect(s, `${url} has no #${id} section`).not.toBe('');
          expect([...s.matchAll(/<li>/g)].length, `${url} #${id} cases`).toBeGreaterThanOrEqual(3);
        }
      }
    });

    it('is a WebPage on each comparison and a CollectionPage with an ItemList on the hub', () => {
      for (const f of compareFiles) {
        const graph = graphOf(read(f), rel(f));
        const types = graph.map((n) => n['@type']);
        expect(types, `${rel(f)} declares no TechArticle`).not.toContain('TechArticle');
        if (pageUrl(f) === COMPARE_HUB) {
          expect(types.filter((t) => t === 'CollectionPage')).toHaveLength(1);
          const list = graph.find((n) => n['@type'] === 'ItemList');
          expect(list?.numberOfItems, 'hub ItemList size').toBe(COMPARE_LEAVES.length);
          const urls = (list!.itemListElement as unknown as { url: string }[]).map((i) => i.url.replace('https://accessflow.io', ''));
          expect(urls.sort()).toEqual([...COMPARE_LEAVES].sort());
        } else {
          expect(types.filter((t) => t === 'WebPage'), `${rel(f)} page node`).toHaveLength(1);
          expect(types).not.toContain('CollectionPage');
        }
      }
    });

    it('claims no ratings or reviews in structured data', () => {
      // There are no ratings, and a Product/Review/AggregateRating node would assert some.
      for (const f of compareFiles) {
        const block = read(f).match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/)![1]!;
        for (const t of ['"Product"', '"Review"', '"AggregateRating"', '"Rating"']) {
          expect(block, `${rel(f)} declares ${t}`).not.toContain(t);
        }
      }
    });

    it('carries the non-affiliation disclaimer, and the hub the AccessFlow-name disambiguation', () => {
      for (const f of compareFiles) {
        const html = read(f);
        const callout = html.match(/<div class="docs-callout info" id="disclaimer">[\s\S]*?<\/div>/)?.[0];
        expect(callout, `${rel(f)} has no disclaimer callout`).toBeDefined();
        expect(text(callout!)).toContain('not affiliated');
        expect(text(callout!)).toContain('Not documented');
        if (pageUrl(f) === COMPARE_HUB) expect(text(callout!)).toContain('Alcor');
      }
    });

    it('never marks a competitor cell "No", and never leaves an AccessFlow cell undocumented', () => {
      for (const url of COMPARE_LEAVES) {
        const html = read(fileOf(url));
        const tables = [...html.matchAll(/<table class="docs-table compare-table"[\s\S]*?<\/table>/g)];
        expect(tables, `${url} compare tables`).toHaveLength(1);
        const table = tables[0]![0];
        const heads = [...slice(table, '<thead>', '</thead>').matchAll(/<th>([^<]*)<\/th>/g)].map((m) => m[1]);
        expect(heads).toHaveLength(3);
        expect(heads[1], `${url} second column is AccessFlow's`).toBe('AccessFlow');
        for (const row of slice(table, '<tbody>', '</tbody>').matchAll(/<tr>([\s\S]*?)<\/tr>/g)) {
          const cells = [...row[1]!.matchAll(/<td[^>]*>([\s\S]*?)<\/td>/g)].map((m) => text(m[1]!));
          expect(cells, `${url} row "${cells[0]}" cell count`).toHaveLength(3);
          // "If you cannot verify a capability, mark the cell Not documented — never No."
          expect(cells[2], `${url} row "${cells[0]}" competitor cell`).not.toMatch(/^no\.?$/i);
          // AccessFlow's own column is written from the repo; it can never be unknown.
          expect(cells[1], `${url} row "${cells[0]}" AccessFlow cell`).not.toContain('Not documented');
        }
      }
    });

    it('links every comparison from every comparison, marking only itself current', () => {
      for (const f of compareFiles) {
        const toc = slice(read(f), '<nav class="docs-toc">', '</nav>');
        for (const url of [COMPARE_HUB, ...COMPARE_LEAVES]) {
          expect(toc, `${rel(f)} sidebar links ${url}`).toContain(`href="${url}"`);
        }
        const current = [...toc.matchAll(/<a href="([^"]+)" class="docs-toc-current" aria-current="page">/g)].map((m) => m[1]);
        expect(current, `${rel(f)} current sidebar entry`).toEqual([pageUrl(f)]);
      }
    });

    it('keeps the four comparisons written per competitor, not templated', () => {
      // The matrix may share row labels; the narrative may not share sentences. Ratchet,
      // not target: the connector pages drifted to 34–50% unique once, unnoticed.
      const MAX_SHARED = 0.2;
      const sentences = (url: string) =>
        new Set(
          text(narrative(read(fileOf(url))))
            .split(/(?<=[.!?])\s+/)
            .map((s) => s.toLowerCase().replace(/\s+/g, ' ').trim())
            .filter((s) => s.split(' ').length >= 8),
        );
      const sets = new Map(COMPARE_LEAVES.map((u) => [u, sentences(u)]));
      for (let i = 0; i < COMPARE_LEAVES.length; i += 1) {
        for (let j = i + 1; j < COMPARE_LEAVES.length; j += 1) {
          const a = sets.get(COMPARE_LEAVES[i]!)!;
          const b = sets.get(COMPARE_LEAVES[j]!)!;
          const shared = [...a].filter((s) => b.has(s));
          const ratio = shared.length / Math.min(a.size, b.size);
          expect(ratio, `${COMPARE_LEAVES[i]} and ${COMPARE_LEAVES[j]} share sentences:\n${shared.join('\n')}`).toBeLessThanOrEqual(MAX_SHARED);
        }
      }
    });
  });

});
