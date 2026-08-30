import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  activeNavHrefs,
  digest,
  expectedCanonical,
  htmlFiles,
  normalizeNav,
  pageUrl,
  slice,
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

/**
 * Screenshots whose -dark.webp twin does not exist yet, so app.js's unconditional
 * -light -> -dark rewrite 404s on theme toggle. Regenerating them is tracked in
 * https://github.com/bablsoft/accessflow/issues/798 — this list must shrink to []
 * when that lands, and the test below fails if an entry becomes unnecessary.
 */
const MISSING_DARK_SCREENSHOTS = [
  '/images/docs/editor',
  '/images/docs/editor-query-templates',
  '/images/docs/editor-schedule',
  '/images/docs/editor-text-to-sql',
  '/images/docs/queries-list',
  '/images/docs/reviews-queue',
  '/images/docs/reviews-queue-bulk',
];

/**
 * Inline style="" attributes left on the site. style-src cannot tighten from
 * 'unsafe-inline' to 'self' until this reaches 0 (website/_headers). Ratchet only
 * downwards — never raise this number to make a new page pass.
 */
const INLINE_STYLE_BUDGET = 42;

describe('website pages', () => {
  it('finds every page on the site', () => {
    expect(files.length).toBeGreaterThanOrEqual(18);
    expect(files.map(rel)).toContain('index.html');
    expect(files.map(rel)).toContain(path.join('ai-agents', 'index.html'));
    expect(files.map(rel)).toContain(path.join('security', 'index.html'));
    expect(files.map(rel)).toContain(path.join('connectors', 'index.html'));
    expect(files.map(rel)).toContain(path.join('use-cases', 'index.html'));
    expect(files.map(rel)).toContain(path.join('roadmap', 'index.html'));
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

  it('marks the nav link for the section it is in, and only that one', () => {
    // normalizeNav strips these markers before hashing so /, /ai-agents/ and the
    // chapters compare equal — which would otherwise retire the check entirely.
    // Both the desktop nav and the mobile panel carry the marker, hence two hits.
    for (const f of files) {
      const nav = slice(read(f), '<header class="nav">', '<main');
      const expected = pageUrl(f).startsWith('/docs/') ? ['/docs/', '/docs/'] : [];
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
    // They are in the nav of every deployed page, in llms.txt, and in the wild.
    const html = read(path.join(websiteRoot, 'index.html'));
    const missing = ['features', 'connectors', 'how', 'use-cases', 'install', 'questions', 'roadmap']
      .filter((id) => !idsOf(html).has(id));
    expect(missing, 'index.html dropped a legacy section id').toEqual([]);
  });

  it('keeps sitemap.xml and the pages on disk in sync both ways', () => {
    const sitemap = readFileSync(path.join(websiteRoot, 'sitemap.xml'), 'utf8');
    const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]!);
    const canonicals = files.map(expectedCanonical);
    expect(canonicals.filter((c) => !locs.includes(c)), 'pages missing from sitemap.xml').toEqual([]);
    expect(locs.filter((l) => !canonicals.includes(l)), 'sitemap.xml URLs with no page on disk').toEqual([]);
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

  it('ships a -dark.webp twin for every -light.webp the pages reference', () => {
    // app.js swapDocsImages() rewrites -light -> -dark unconditionally on toggle,
    // so a base name without both twins is a live 404 for anyone on light OS theme.
    const missing = new Set<string>();
    for (const f of files) {
      for (const m of read(f).matchAll(/(\/images\/[a-z0-9/-]+)-light\.webp/g)) {
        if (!existsSync(path.join(websiteRoot, `${m[1]!}-dark.webp`))) missing.add(m[1]!);
      }
    }
    expect([...missing].filter((b) => !MISSING_DARK_SCREENSHOTS.includes(b)).sort()).toEqual([]);
    // Keeps the allowlist honest: an entry that no longer fails must be deleted.
    expect(MISSING_DARK_SCREENSHOTS.filter((b) => !missing.has(b)), 'stale allowlist entries').toEqual([]);
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
    // Same reason the dark-screenshot allowlist above is asserted exactly consumed.
    expect(total, 'inline style="" attributes site-wide').toBe(INLINE_STYLE_BUDGET);
  });

});
