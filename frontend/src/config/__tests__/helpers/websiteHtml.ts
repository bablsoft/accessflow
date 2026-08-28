import { createHash } from 'node:crypto';
import { readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Shared helpers for the site-wide website guards in websitePages.test.ts and the
 * docs-specific ones in websiteDocs.test.ts, so the two agree on how a page is
 * located, sliced and hashed.
 *
 * websiteCsp.test.ts and websiteSecurityTxt.test.ts deliberately keep their own
 * walkers — CSP hashes must cover every .html including the Google verification
 * stub excluded here.
 */
const here = path.dirname(fileURLToPath(import.meta.url));

/** Absolute path of the website/ directory. */
export const websiteRoot = path.resolve(here, '../../../../../website');

/** Google Search Console verification token — a 0-line stub, not a page. */
const NOT_A_PAGE = ['googlef4908e4bf779aae8.html'];

/**
 * Every guardable page under `root`, sorted. Paths in `exclude` are skipped
 * (matched relative to `root`, so a same-named page elsewhere is still guarded),
 * as are dot-directories and node_modules.
 */
export const htmlFiles = (
  root: string,
  { exclude = NOT_A_PAGE }: { exclude?: readonly string[] } = {},
): string[] => {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.name.startsWith('.') || entry.name === 'node_modules') continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.html') && !exclude.includes(path.relative(root, full))) {
        out.push(full);
      }
    }
  };
  walk(root);
  return out.sort();
};

/** The substring of `s` from the first `a` up to the next `b`, or '' if either is absent. */
export const slice = (s: string, a: string, b: string): string => {
  const i = s.indexOf(a);
  if (i === -1) return '';
  const j = s.indexOf(b, i);
  if (j === -1) return '';
  return s.slice(i, j);
};

export const digest = (s: string): string =>
  createHash('sha256').update(s).digest('hex').slice(0, 12);

/** '<website>/docs/configuration/ai/index.html' -> 'https://accessflow.io/docs/configuration/ai/' */
export const expectedCanonical = (file: string): string => {
  const rel = path.relative(websiteRoot, file).replace(/index\.html$/, '').replace(/\\/g, '/');
  return `https://accessflow.io/${rel}`;
};

/** The site-root-relative URL a page is served at: '.../docs/install/index.html' -> '/docs/install/'. */
export const pageUrl = (file: string): string =>
  expectedCanonical(file).replace('https://accessflow.io', '');

/**
 * Strips the per-page active-link markers so navs can be compared byte-for-byte.
 * `aria-current="page"` and the `nav-link-active` class legitimately differ per
 * page — every other byte of the nav must not.
 *
 * The class is removed as a token rather than as the exact string `class="nav-link-active"`,
 * so adding a second class to the active link stays a no-op here instead of failing
 * the nav test with a misleading "nav content drift". Which link carries the marker
 * is asserted separately — stripping it here must not stop it being checked at all.
 */
export const normalizeNav = (nav: string): string =>
  nav
    .replace(/ aria-current="page"/g, '')
    .replace(/\s*\bnav-link-active\b/g, '')
    .replace(/ class=""/g, '');

/** The hrefs of the nav links marked as the current page, in document order. */
export const activeNavHrefs = (nav: string): string[] =>
  [...nav.matchAll(/<a href="([^"]+)"[^>]*aria-current="page"/g)].map((m) => m[1]!);
