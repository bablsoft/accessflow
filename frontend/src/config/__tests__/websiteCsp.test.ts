import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * The public site pins a `script-src 'sha256-…'` for its inline theme-bootstrap
 * script (the one that reads localStorage before first paint so the page does not
 * flash the wrong theme).
 *
 * website/ has no build step and no test runner of its own, so nothing else would
 * notice if someone edited that script by a single character: the hash would stop
 * matching, the browser would silently block the script, and the theme flash would
 * come back in production only. This test is that missing guard.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const website = path.resolve(here, '../../../../website');

const readHtmlFiles = (): string[] => {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.html')) out.push(full);
    }
  };
  walk(website);
  return out;
};

/** Inline <script> blocks only — external src and ld+json data blocks are exempt. */
const inlineScripts = (html: string): string[] =>
  [...html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)]
    .map((m) => ({ attrs: m[1] ?? '', body: m[2] ?? '' }))
    .filter(({ attrs }) => !attrs.includes('src=') && !attrs.includes('ld+json'))
    .map(({ body }) => body);

const sha256 = (body: string) => `sha256-${createHash('sha256').update(body).digest('base64')}`;

describe('website CSP', () => {
  const headers = readFileSync(path.join(website, '_headers'), 'utf8');
  const htmlFiles = readHtmlFiles();

  it('finds the HTML files it is meant to guard', () => {
    expect(htmlFiles.length).toBeGreaterThanOrEqual(23);
  });

  it('every inline script in the site is allowed by a hash in _headers', () => {
    const unhashed: string[] = [];
    for (const file of htmlFiles) {
      for (const body of inlineScripts(readFileSync(file, 'utf8'))) {
        if (!headers.includes(sha256(body))) {
          unhashed.push(`${path.relative(website, file)} -> ${sha256(body)}`);
        }
      }
    }
    // If this fails, the inline script changed. Paste the printed hash into the
    // Content-Security-Policy line in website/_headers.
    expect(unhashed).toEqual([]);
  });

  it('declares no hash that no longer matches any inline script', () => {
    const live = new Set(
      htmlFiles.flatMap((f) => inlineScripts(readFileSync(f, 'utf8')).map(sha256)),
    );
    const declared = [...headers.matchAll(/'(sha256-[A-Za-z0-9+/=]+)'/g)].map((m) => m[1]!);
    expect(declared.length).toBeGreaterThan(0);
    expect(declared.filter((h) => !live.has(h))).toEqual([]);
  });

  it('permits exactly one third party, and only where it is needed', () => {
    // The site was zero-third-party until Cloudflare Web Analytics. That is enabled
    // with AUTOMATIC injection, so the beacon <script src> is added at the edge and
    // exists in no file in this repo — which is how script-src silently blocked it
    // for its whole life. Pinning the origin list here means the next third party
    // has to be an explicit edit to this test, not a quiet addition to _headers.
    const ALLOWED = ['https://static.cloudflareinsights.com', 'https://cloudflareinsights.com'];
    const csp = headers.match(/Content-Security-Policy:\s*(.+)/)?.[1] ?? '';
    const origins = [...csp.matchAll(/https?:\/\/[^\s;']+/g)].map((m) => m[0]);
    expect([...new Set(origins)].sort(), 'unexpected third-party origin in CSP').toEqual(
      [...ALLOWED].sort(),
    );
    // The beacon loads as a script and posts its payload; nothing else is opened up.
    const directive = (name: string) =>
      csp.split(';').map((d) => d.trim()).find((d) => d.startsWith(`${name} `)) ?? '';
    expect(directive('script-src')).toContain('https://static.cloudflareinsights.com');
    expect(directive('connect-src')).toContain('https://cloudflareinsights.com');
    for (const d of ['img-src', 'font-src', 'style-src', 'default-src', 'base-uri', 'form-action']) {
      expect(directive(d), `${d} must stay first-party`).not.toMatch(/https?:\/\//);
    }
  });

  it('keeps the policy locked to our own origin', () => {
    const csp = headers.match(/Content-Security-Policy:\s*(.+)/)?.[1] ?? '';
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("frame-ancestors 'none'");
    // Fonts are self-hosted precisely so no third-party origin is needed.
    expect(csp).not.toContain('fonts.googleapis.com');
    expect(csp).not.toContain('fonts.gstatic.com');
    // Scripts must never fall back to blanket inline execution.
    expect(csp).not.toMatch(/script-src[^;]*'unsafe-inline'/);
    expect(csp).not.toMatch(/script-src[^;]*'unsafe-eval'/);
  });
});
