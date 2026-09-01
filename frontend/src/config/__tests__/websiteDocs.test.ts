import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { htmlFiles, pageUrl, slice, websiteRoot } from './helpers/websiteHtml';

/**
 * Docs-specific guards. Everything that is true of *any* page on the site — nav and
 * footer identity, canonicals, headings, descriptions, duplicate ids, dead fragments,
 * sitemap membership — moved to websitePages.test.ts in AF-794, which runs it against
 * every page on the site rather than the docs chapters alone.
 *
 * What is left is the part with no meaning outside docs/: every chapter must link every
 * other chapter from its sidebar, and cross-chapter links must resolve.
 */
const docsRoot = path.join(websiteRoot, 'docs');

describe('website docs chapters', () => {
  const files = htmlFiles(docsRoot);
  const read = (f: string) => readFileSync(f, 'utf8');
  const rel = (f: string) => path.relative(websiteRoot, f);

  it('finds every chapter page', () => {
    expect(files.length).toBeGreaterThanOrEqual(22);
  });

  it('links every chapter from every chapter sidebar', () => {
    const urls = files.map(pageUrl);
    for (const f of files) {
      const html = read(f);
      const missing = urls.filter((u) => !html.includes(`href="${u}"`));
      expect(missing, `${rel(f)} sidebar is missing chapters`).toEqual([]);
    }
  });

  it('has no dead cross-chapter links', () => {
    const idsByUrl = new Map(
      files.map((f) => [
        pageUrl(f),
        new Set([...read(f).matchAll(/id="([a-z0-9-]+)"/g)].map((m) => m[1]!)),
      ]),
    );
    for (const f of files) {
      const main = slice(read(f), '<main', '</main>');
      const dead: string[] = [];
      for (const m of main.matchAll(/href="(\/docs\/[a-z0-9/-]*)(?:#([a-z0-9-]+))?"/g)) {
        const [, url, frag] = m;
        const ids = idsByUrl.get(url!);
        if (!ids) dead.push(`${url} (no such chapter)`);
        else if (frag && !ids.has(frag)) dead.push(`${url}#${frag} (no such id)`);
      }
      expect([...new Set(dead)], `${rel(f)} has dead cross-chapter links`).toEqual([]);
    }
  });
});
