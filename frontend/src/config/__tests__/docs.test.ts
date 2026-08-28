import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { DOCS_ANCHOR_PAGES, DOCS_ANCHORS, DOCS_BASE_URL, docsUrl } from '@/config/docs';

const here = path.dirname(fileURLToPath(import.meta.url));
const websiteDocs = path.resolve(here, '../../../../website/docs');
const readChapter = (page: string) =>
  readFileSync(path.join(websiteDocs, page, 'index.html'), 'utf8');

describe('docs config', () => {
  it('builds an absolute anchored URL pointing at the owning chapter', () => {
    expect(docsUrl('cfg-users')).toBe(
      'https://accessflow.io/docs/configuration/users-roles/#cfg-users',
    );
    expect(docsUrl('cfg-saml')).toBe(
      'https://accessflow.io/docs/configuration/auth/#cfg-saml',
    );
  });

  it('base URL ends in a slash and matches the docs hub canonical URL', () => {
    expect(DOCS_BASE_URL).toMatch(/\/$/);
    expect(readChapter('')).toContain(`<link rel="canonical" href="${DOCS_BASE_URL}" />`);
  });

  it('declares no duplicate anchors', () => {
    expect(new Set(DOCS_ANCHORS).size).toBe(DOCS_ANCHORS.length);
  });

  it('every chapter path ends in a slash', () => {
    for (const page of Object.values(DOCS_ANCHOR_PAGES)) {
      expect(page).toMatch(/\/$/);
    }
  });

  // Guards both halves of the contract: a typo here, a section moved to another
  // chapter, or a docs section deleted outright turns every in-app "View docs"
  // link into a no-op scroll. Fail loudly instead.
  it.each(DOCS_ANCHORS)('anchor %s exists in the chapter that claims it', (anchor) => {
    expect(readChapter(DOCS_ANCHOR_PAGES[anchor])).toContain(`id="${anchor}"`);
  });

  // The split left a PERMANENT forwarder in website/app.js for the old one-page
  // /docs/#anchor form, because already-released self-hosted frontends still emit
  // it and never update. If the two disagree, those installs land on the wrong
  // chapter.
  it('website/app.js forwards every anchor to the chapter this map declares', () => {
    const appJs = readFileSync(path.resolve(websiteDocs, '../app.js'), 'utf8');
    for (const anchor of DOCS_ANCHORS) {
      expect(appJs).toContain(`'${anchor}': '/docs/${DOCS_ANCHOR_PAGES[anchor]}'`);
    }
  });

  // e2e specs assert the rendered href as a literal string (deliberately — following
  // the link would make that suite depend on the network). Those literals are a
  // third copy of this mapping, and they drift silently: moving a section between
  // chapters turns them stale and only surfaces after a full e2e run in CI.
  it('every docs URL hardcoded in e2e/ matches this map', () => {
    const e2eTests = path.resolve(here, '../../../../e2e/tests');
    const stale: string[] = [];
    for (const file of readdirSync(e2eTests).filter((f) => f.endsWith('.spec.ts'))) {
      const src = readFileSync(path.join(e2eTests, file), 'utf8');
      for (const m of src.matchAll(/'(https:\/\/accessflow\.io\/docs\/[^']*)'/g)) {
        const url = m[1]!;
        const anchor = url.split('#')[1];
        if (!anchor) continue;
        const expected = docsUrl(anchor as (typeof DOCS_ANCHORS)[number]);
        if (url !== expected) stale.push(`${file}: ${url} should be ${expected}`);
      }
    }
    expect(stale).toEqual([]);
  });
});
