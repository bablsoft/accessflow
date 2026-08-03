import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * RFC 9116 makes `Expires` mandatory, and a security.txt past that date is
 * *invalid* — not merely stale. It keeps serving 200 while every scanner and
 * researcher tooling treats it as unusable, so the failure is silent and the
 * file ends up worse than not shipping one at all.
 *
 * website/ has no build step and the repo has no scheduled workflow, so nothing
 * else would ever notice the date passing. These assertions are the alarm: they
 * fail loudly while there is still plenty of time to act.
 *
 * To renew: bump `Expires` in website/.well-known/security.txt to one year out
 * and re-check that the Contact URL still accepts reports.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const securityTxt = path.resolve(here, '../../../../website/.well-known/security.txt');

const DAY_MS = 24 * 60 * 60 * 1000;
const WARN_WITHIN_DAYS = 90;
const FAIL_WITHIN_DAYS = 30;

const fields = (): Record<string, string> => {
  const out: Record<string, string> = {};
  for (const line of readFileSync(securityTxt, 'utf8').split('\n')) {
    const m = line.match(/^([A-Za-z-]+):\s*(.+?)\s*$/);
    if (m) out[m[1]!] = m[2]!;
  }
  return out;
};

const daysUntilExpiry = (): number =>
  Math.floor((new Date(fields().Expires!).getTime() - Date.now()) / DAY_MS);

describe('website security.txt', () => {
  it('carries the fields RFC 9116 requires', () => {
    const f = fields();
    expect(f.Contact, 'Contact is mandatory').toBeTruthy();
    expect(f.Expires, 'Expires is mandatory').toBeTruthy();
    expect(new Date(f.Expires!).toString()).not.toBe('Invalid Date');
  });

  it('points Canonical at the path it is actually served from', () => {
    expect(fields().Canonical).toBe(
      'https://accessflow.bablsoft.com/.well-known/security.txt',
    );
  });

  it('has not expired', () => {
    const days = daysUntilExpiry();
    expect(
      days,
      `security.txt EXPIRED ${Math.abs(days)} days ago — it is invalid, not just old. ` +
        `Bump Expires in website/.well-known/security.txt to one year out.`,
    ).toBeGreaterThan(0);
  });

  it(`does not expire within ${FAIL_WITHIN_DAYS} days`, () => {
    const days = daysUntilExpiry();
    if (days <= WARN_WITHIN_DAYS && days > FAIL_WITHIN_DAYS) {
      // Early nudge: still passing, but the clock is visible in CI output.
      console.warn(
        `[security.txt] expires in ${days} days — renew it soon ` +
          `(this test starts failing at ${FAIL_WITHIN_DAYS} days).`,
      );
    }
    expect(
      days,
      `security.txt expires in ${days} days. Renew it now: set Expires in ` +
        `website/.well-known/security.txt to one year from today, and confirm the ` +
        `Contact URL still accepts reports.`,
    ).toBeGreaterThan(FAIL_WITHIN_DAYS);
  });

  it('does not claim validity more than a year out', () => {
    // RFC 9116 §2.5.5 recommends under a year; scanners flag longer windows.
    expect(daysUntilExpiry()).toBeLessThanOrEqual(366);
  });
});
