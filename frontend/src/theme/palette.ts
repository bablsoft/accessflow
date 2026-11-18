// The colours Ant Design needs as literal values (its algorithms derive ramps from them, so a
// `var(--…)` reference is not enough). `src/styles/tokens.css` carries the same values for plain
// CSS; `tokenParity.test.ts` fails the build when the two disagree.

export interface Palette {
  bg: string;
  bgElev: string;
  bgSubtle: string;
  bgHover: string;
  bgActive: string;
  bgSidebar: string;
  border: string;
  borderStrong: string;
  fg: string;
  fgMuted: string;
  fgSubtle: string;
  fgFaint: string;
  accent: string;
  info: string;
  success: string;
  warning: string;
  danger: string;
}

/** The CSS custom property in `tokens.css` that mirrors each palette entry. */
export const PALETTE_CSS_VARS: Record<keyof Palette, string> = {
  bg: '--bg',
  bgElev: '--bg-elev',
  bgSubtle: '--bg-subtle',
  bgHover: '--bg-hover',
  bgActive: '--bg-active',
  bgSidebar: '--bg-sidebar',
  border: '--border',
  borderStrong: '--border-strong',
  fg: '--fg',
  fgMuted: '--fg-muted',
  fgSubtle: '--fg-subtle',
  fgFaint: '--fg-faint',
  accent: '--accent',
  info: '--status-info',
  success: '--risk-low',
  warning: '--status-warn',
  danger: '--risk-crit',
};

export const lightPalette: Palette = {
  bg: '#fafafa',
  bgElev: '#ffffff',
  bgSubtle: '#f9f9fb',
  bgHover: '#f4f4f5',
  bgActive: '#ececef',
  bgSidebar: '#ffffff',
  border: '#e7e7ea',
  borderStrong: '#d4d4d8',
  fg: '#18181b',
  fgMuted: '#52525b',
  fgSubtle: '#5f5f69',
  fgFaint: '#71717a',
  accent: '#6366f1',
  info: '#1d4ed8',
  success: '#15803d',
  warning: '#a16207',
  danger: '#b91c1c',
};

export const darkPalette: Palette = {
  bg: '#09090b',
  bgElev: '#131316',
  bgSubtle: '#1a1a1f',
  bgHover: '#1c1c20',
  bgActive: '#232328',
  bgSidebar: '#0c0c0e',
  border: '#232328',
  borderStrong: '#2e2e35',
  fg: '#fafafa',
  fgMuted: '#d4d4d8',
  fgSubtle: '#a1a1aa',
  fgFaint: '#8b8b94',
  accent: '#818cf8',
  info: '#60a5fa',
  success: '#4ade80',
  warning: '#fbbf24',
  danger: '#f87171',
};

/** Durations shared by CSS (`--dur-*`), Ant Design's motion tokens and the Motion presets. */
export const DURATION_MS = { fast: 120, base: 180, slow: 260 } as const;

export const EASE_OUT = [0.16, 1, 0.3, 1] as const;
export const EASE_IN = [0.7, 0, 0.84, 0] as const;
