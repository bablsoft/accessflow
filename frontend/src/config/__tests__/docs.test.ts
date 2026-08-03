import { readFileSync } from 'node:fs';
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
      'https://accessflow.bablsoft.com/docs/configuration/users-roles/#cfg-users',
    );
    expect(docsUrl('cfg-saml')).toBe(
      'https://accessflow.bablsoft.com/docs/configuration/auth/#cfg-saml',
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
});
