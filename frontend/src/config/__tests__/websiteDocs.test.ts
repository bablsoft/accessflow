import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * The public docs are one hand-maintained HTML file per chapter, with the nav,
 * sidebar and footer duplicated into each. That is the deliberate trade for
 * keeping website/ buildless (see website/README.md), but it means a nav or
 * footer change is a 13-file edit — and missing one file is silent.
 *
 * These assertions are the guard: they cannot reduce the edit cost, but they
 * make a partial edit fail CI instead of shipping an inconsistent site.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const website = path.resolve(here, '../../../../website');
const docsRoot = path.join(website, 'docs');

const chapterFiles = (): string[] => {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      if (e.isDirectory()) walk(path.join(dir, e.name));
      else if (e.name === 'index.html') out.push(path.join(dir, e.name));
    }
  };
  walk(docsRoot);
  return out.sort();
};

const slice = (s: string, a: string, b: string): string => {
  const i = s.indexOf(a);
  const j = s.indexOf(b, i);
  if (i === -1 || j === -1) return '';
  return s.slice(i, j);
};

const digest = (s: string) => createHash('sha256').update(s).digest('hex').slice(0, 12);

/** '/docs/configuration/ai/index.html' -> 'https://accessflow.bablsoft.com/docs/configuration/ai/' */
const expectedCanonical = (file: string) => {
  const rel = path.relative(website, file).replace(/index\.html$/, '').replace(/\\/g, '/');
  return `https://accessflow.bablsoft.com/${rel}`;
};

describe('website docs chapters', () => {
  const files = chapterFiles();
  const read = (f: string) => readFileSync(f, 'utf8');
  const rel = (f: string) => path.relative(website, f);

  it('finds every chapter page', () => {
    expect(files.length).toBeGreaterThanOrEqual(12);
  });

  it('shares a byte-identical nav across every chapter', () => {
    const groups = new Map<string, string[]>();
    for (const f of files) {
      const nav = slice(read(f), '<header class="nav">', '<main');
      expect(nav, `${rel(f)} has no site nav`).not.toBe('');
      const k = digest(nav);
      groups.set(k, [...(groups.get(k) ?? []), rel(f)]);
    }
    // More than one group means someone edited the nav in some files but not all.
    expect([...groups.values()]).toHaveLength(1);
  });

  it('shares a byte-identical footer across every chapter', () => {
    const groups = new Map<string, string[]>();
    for (const f of files) {
      const foot = slice(read(f), '<footer', '</body>');
      expect(foot, `${rel(f)} has no footer`).not.toBe('');
      const k = digest(foot);
      groups.set(k, [...(groups.get(k) ?? []), rel(f)]);
    }
    expect([...groups.values()]).toHaveLength(1);
  });

  it('links every chapter from every chapter sidebar', () => {
    const urls = files.map((f) => expectedCanonical(f).replace('https://accessflow.bablsoft.com', ''));
    for (const f of files) {
      const html = read(f);
      const missing = urls.filter((u) => !html.includes(`href="${u}"`));
      expect(missing, `${rel(f)} sidebar is missing chapters`).toEqual([]);
    }
  });

  it('gives each chapter a self-referencing canonical matching its path', () => {
    for (const f of files) {
      expect(read(f), rel(f)).toContain(`<link rel="canonical" href="${expectedCanonical(f)}" />`);
    }
  });

  it('gives each chapter exactly one h1 and no skipped heading levels', () => {
    for (const f of files) {
      const html = read(f);
      const main = slice(html, '<main', '</main>');
      const levels = [...main.matchAll(/<h([1-6])[^>]*>/g)].map((m) => Number(m[1]));
      expect(levels.filter((l) => l === 1), `${rel(f)} h1 count`).toHaveLength(1);
      const skips = levels.slice(1).filter((l, i) => l > levels[i]! + 1);
      expect(skips, `${rel(f)} skips a heading level`).toEqual([]);
    }
  });

  it('keeps every chapter description within the SERP snippet limit', () => {
    for (const f of files) {
      const m = read(f).match(/<meta name="description" content="([\s\S]*?)" \/>/);
      expect(m, `${rel(f)} has no meta description`).not.toBeNull();
      const rendered = m![1]!.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&lt;/g, '<');
      expect(rendered.length, `${rel(f)} description is ${rendered.length} chars`).toBeLessThanOrEqual(160);
    }
  });

  it('declares no duplicate element ids within a chapter', () => {
    for (const f of files) {
      const ids = [...read(f).matchAll(/id="([a-z0-9-]+)"/g)].map((m) => m[1]!);
      const dupes = [...new Set(ids.filter((id, i) => ids.indexOf(id) !== i))];
      expect(dupes, `${rel(f)} has duplicate ids`).toEqual([]);
    }
  });

  it('has no dead same-page fragment links', () => {
    for (const f of files) {
      const html = read(f);
      const ids = new Set([...html.matchAll(/id="([a-z0-9-]+)"/g)].map((m) => m[1]!));
      const main = slice(html, '<main', '</main>');
      const dead = [...main.matchAll(/href="#([a-z0-9-]+)"/g)]
        .map((m) => m[1]!)
        .filter((frag) => !ids.has(frag));
      expect([...new Set(dead)], `${rel(f)} links to missing anchors`).toEqual([]);
    }
  });

  it('has no dead cross-chapter links', () => {
    const idsByUrl = new Map<string, Set<string>>();
    for (const f of files) {
      const url = expectedCanonical(f).replace('https://accessflow.bablsoft.com', '');
      idsByUrl.set(url, new Set([...read(f).matchAll(/id="([a-z0-9-]+)"/g)].map((m) => m[1]!)));
    }
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

  it('lists every chapter in sitemap.xml', () => {
    const sitemap = readFileSync(path.join(website, 'sitemap.xml'), 'utf8');
    for (const f of files) {
      expect(sitemap, `sitemap.xml is missing ${rel(f)}`).toContain(`<loc>${expectedCanonical(f)}</loc>`);
    }
  });
});
