import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  DURATION_MS,
  PALETTE_CSS_VARS,
  darkPalette,
  lightPalette,
  type Palette,
} from '../palette';

const css = readFileSync(resolve(__dirname, '../../styles/tokens.css'), 'utf8');

function parseBlock(selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`);
  expect(start).toBeGreaterThanOrEqual(0);
  const body = css.slice(start, css.indexOf('\n}', start));
  const vars: Record<string, string> = {};
  for (const match of body.matchAll(/(--[\w-]+):\s*([^;]+);/g)) {
    vars[match[1] as string] = (match[2] as string).trim();
  }
  return vars;
}

const light = parseBlock(':root');
const dark = { ...light, ...parseBlock("[data-theme='dark']") };

function luminance(hex: string): number {
  const channels = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const [r, g, b] = channels.map((c) =>
    c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4,
  ) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

const themes: [string, Palette, Record<string, string>][] = [
  ['light', lightPalette, light],
  ['dark', darkPalette, dark],
];

describe.each(themes)('%s tokens', (_name, palette, vars) => {
  it('match palette.ts', () => {
    for (const [key, cssVar] of Object.entries(PALETTE_CSS_VARS)) {
      expect(vars[cssVar], cssVar).toBe(palette[key as keyof Palette]);
    }
  });

  it('keep every text tier at WCAG AA on both surfaces', () => {
    for (const tier of ['--fg', '--fg-muted', '--fg-subtle', '--fg-faint']) {
      for (const surface of ['--bg', '--bg-elev']) {
        const ratio = contrast(vars[tier] as string, vars[surface] as string);
        expect(ratio, `${tier} on ${surface}`).toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  it('keep status and risk text readable on its own tint', () => {
    for (const name of ['risk-low', 'risk-med', 'risk-high', 'risk-crit', 'status-info', 'status-warn', 'status-neutral']) {
      const ratio = contrast(vars[`--${name}`] as string, vars[`--${name}-bg`] as string);
      expect(ratio, name).toBeGreaterThanOrEqual(4.5);
    }
  });
});

describe('motion tokens', () => {
  it('match the shared durations', () => {
    expect(light['--dur-1']).toBe(`${DURATION_MS.fast}ms`);
    expect(light['--dur-2']).toBe(`${DURATION_MS.base}ms`);
    expect(light['--dur-3']).toBe(`${DURATION_MS.slow}ms`);
  });
});
