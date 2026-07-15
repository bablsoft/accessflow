import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { DOCS_ANCHORS, DOCS_BASE_URL, docsUrl } from '@/config/docs';

const here = path.dirname(fileURLToPath(import.meta.url));
const docsHtml = readFileSync(path.resolve(here, '../../../../website/docs/index.html'), 'utf8');

describe('docs config', () => {
  it('builds an absolute anchored URL', () => {
    expect(docsUrl('cfg-users')).toBe('https://accessflow.bablsoft.com/docs/#cfg-users');
  });

  it('base URL ends in a slash and matches the docs page canonical URL', () => {
    expect(DOCS_BASE_URL).toMatch(/\/$/);
    expect(docsHtml).toContain(`<link rel="canonical" href="${DOCS_BASE_URL}" />`);
  });

  it('declares no duplicate anchors', () => {
    expect(new Set(DOCS_ANCHORS).size).toBe(DOCS_ANCHORS.length);
  });

  // Guards both halves of the contract: a typo here, or a docs section deleted
  // from website/docs/index.html, turns every in-app "View docs" link into a
  // no-op scroll. Fail loudly instead.
  it.each(DOCS_ANCHORS)('anchor %s exists in website/docs/index.html', (anchor) => {
    expect(docsHtml).toContain(`id="${anchor}"`);
  });
});
