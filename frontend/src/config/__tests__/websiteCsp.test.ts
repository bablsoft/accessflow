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

/** The Cloudflare AI Search instance behind the chat bubble (custom domain). */
const CHAT_ORIGIN = 'https://chat.accessflow.io';

const cspDirective = (csp: string) => (name: string) =>
  csp
    .split(';')
    .map((d) => d.trim())
    .find((d) => d.startsWith(`${name} `)) ?? '';

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

  it('permits exactly two third parties, and only where each is needed', () => {
    // The site was zero-third-party until Cloudflare Web Analytics. That is enabled
    // with AUTOMATIC injection, so the beacon <script src> is added at the edge and
    // exists in no file in this repo — which is how script-src silently blocked it
    // for its whole life. The second is the Cloudflare AI Search instance behind the
    // chat bubble app.js injects (see the 'website chat bubble' block below).
    // Pinning the origin list here means the next third party has to be an explicit
    // edit to this test, not a quiet addition to _headers.
    const ALLOWED = [
      'https://static.cloudflareinsights.com',
      'https://cloudflareinsights.com',
      CHAT_ORIGIN,
    ];
    const csp = headers.match(/Content-Security-Policy:\s*(.+)/)?.[1] ?? '';
    const origins = [...csp.matchAll(/https?:\/\/[^\s;']+/g)].map((m) => m[0]);
    expect([...new Set(origins)].sort(), 'unexpected third-party origin in CSP').toEqual(
      [...ALLOWED].sort(),
    );
    // The beacon loads as a script and posts its payload; the chat widget loads as a
    // script and streams its completions; nothing else is opened up.
    const directive = cspDirective(csp);
    expect(directive('script-src')).toContain('https://static.cloudflareinsights.com');
    expect(directive('connect-src')).toContain('https://cloudflareinsights.com');
    expect(directive('script-src')).toContain(CHAT_ORIGIN);
    expect(directive('connect-src')).toContain(CHAT_ORIGIN);
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

describe('website chat bubble', () => {
  // website/ has no build step, so the chat bubble is injected by app.js on every
  // page rather than authored into 59 hand-copied HTML files. That makes app.js the
  // only place the widget's origin, version pin and attributes live — and the only
  // place a drift away from the CSP above could start. These checks tie the two
  // files together and keep the user-facing contract (branding hidden, site-token
  // theming) from silently disappearing.
  const headers = readFileSync(path.join(website, '_headers'), 'utf8');
  const csp = headers.match(/Content-Security-Policy:\s*(.+)/)?.[1] ?? '';
  const directive = cspDirective(csp);
  const appJs = readFileSync(path.join(website, 'app.js'), 'utf8');
  const stylesCss = readFileSync(path.join(website, 'styles.css'), 'utf8');

  it('loads one version-pinned chat bundle from an origin script-src allows', () => {
    const scripts = [...appJs.matchAll(/https:\/\/[^'"\s]+search-snippet[^'"\s]*\.js/g)].map(
      (m) => m[0],
    );
    expect(scripts).toHaveLength(1);
    const url = new URL(scripts[0]!);
    expect(url.origin).toBe(CHAT_ORIGIN);
    // The unversioned root path is what the upstream README shows, but the custom
    // domain answers it with 401; only the immutable /assets/v<x.y.z>/ files exist.
    expect(url.pathname).toMatch(/^\/assets\/v\d+\.\d+\.\d+\/search-snippet\.chat\.es\.js$/);
    expect(directive('script-src')).toContain(url.origin);
  });

  it('points api-url at an origin connect-src allows', () => {
    const apiUrl = appJs.match(/CHAT_API = '([^']+)'/)?.[1];
    expect(apiUrl).toBeDefined();
    expect(new URL(apiUrl!).origin).toBe(CHAT_ORIGIN);
    expect(directive('connect-src')).toContain(CHAT_ORIGIN);
    expect(appJs).toContain("setAttribute('api-url', CHAT_API)");
  });

  it('hides the vendor branding', () => {
    expect(appJs).toContain("setAttribute('hide-branding', 'true')");
  });

  it('persists the conversation per tab, never per browser', () => {
    // The chat survives navigation through a snapshot in sessionStorage, which the
    // browser drops with the tab. localStorage would outlive the visit; the only
    // thing the site keeps there is the theme choice.
    expect(appJs).toContain("sessionStorage.setItem(CHAT_STORAGE_KEY");
    expect(appJs).toContain("CHAT_STORAGE_KEY = 'accessflow.chat'");
    const localStorageKeys = [...appJs.matchAll(/localStorage\.\w+\((\w+)/g)].map((m) => m[1]);
    expect(new Set(localStorageKeys)).toEqual(new Set(['STORAGE_KEY']));
    expect(appJs).toContain("STORAGE_KEY = 'accessflow.theme'");
  });

  it('keeps the widget themed from the site tokens', () => {
    const block = stylesCss.match(/chat-bubble-snippet \{([\s\S]*?)\n\}/)?.[1] ?? '';
    expect(block).not.toBe('');
    for (const prop of [
      '--search-snippet-primary-color: var(--accent)',
      '--search-snippet-background: var(--bg-1)',
      '--search-snippet-text-color: var(--fg)',
      '--search-snippet-font-family: var(--sans)',
    ]) {
      expect(block).toContain(prop);
    }
    // app.js mirrors the site theme onto the element; styles.css keys color-scheme
    // off that same attribute.
    expect(appJs).toContain("setAttribute('theme', resolveTheme())");
    expect(stylesCss).toContain('chat-bubble-snippet[theme="dark"] { color-scheme: dark; }');
  });
});
