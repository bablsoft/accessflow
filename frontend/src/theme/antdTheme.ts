import { theme, type ThemeConfig } from 'antd';
import { DURATION_MS, darkPalette, lightPalette, type Palette } from './palette';

const fontFamily =
  "'Geist Variable', 'Geist', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
const fontFamilyCode =
  "'Geist Mono Variable', 'Geist Mono', 'SF Mono', Menlo, Consolas, monospace";

const seconds = (ms: number) => `${ms / 1000}s`;

const sharedToken = {
  fontFamily,
  fontFamilyCode,
  fontSize: 13,
  borderRadius: 6,
  borderRadiusSM: 4,
  borderRadiusLG: 8,
  controlHeight: 32,
  controlHeightSM: 26,
  motionDurationFast: seconds(DURATION_MS.fast),
  motionDurationMid: seconds(DURATION_MS.base),
  motionDurationSlow: seconds(DURATION_MS.slow),
  motionEaseOut: 'cubic-bezier(0.16, 1, 0.3, 1)',
  motionEaseInOut: 'cubic-bezier(0.65, 0, 0.35, 1)',
};

function buildTheme(p: Palette, dark: boolean): ThemeConfig {
  return {
    algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
    cssVar: { key: 'af' },
    hashed: false,
    token: {
      ...sharedToken,
      colorPrimary: p.accent,
      colorInfo: p.info,
      colorSuccess: p.success,
      colorWarning: p.warning,
      colorError: p.danger,
      colorLink: p.accent,
      colorBgBase: p.bg,
      colorBgContainer: p.bgElev,
      colorBgElevated: p.bgElev,
      colorBgLayout: p.bg,
      colorBorder: p.border,
      colorBorderSecondary: dark ? p.bgHover : p.bgActive,
      colorText: p.fg,
      colorTextSecondary: p.fgMuted,
      colorTextTertiary: p.fgSubtle,
      colorTextQuaternary: p.fgFaint,
      colorTextPlaceholder: p.fgFaint,
    },
    components: {
      Layout: {
        bodyBg: p.bg,
        headerBg: p.bgElev,
        siderBg: p.bgSidebar,
      },
      Menu: {
        itemBg: 'transparent',
        itemSelectedBg: p.bgActive,
        itemHoverBg: p.bgHover,
        itemSelectedColor: p.fg,
        itemColor: p.fgMuted,
      },
      Table: {
        headerBg: p.bgHover,
        headerColor: p.fgMuted,
        headerSplitColor: dark ? p.border : p.bgActive,
        rowHoverBg: p.bgSubtle,
        borderColor: dark ? p.border : p.bgActive,
        headerBorderRadius: 8,
        cellPaddingBlock: 12,
        cellPaddingInline: 14,
      },
      Button: {
        fontWeight: 500,
        primaryShadow: 'none',
        defaultShadow: 'none',
        dangerShadow: 'none',
      },
      Card: {
        headerFontSize: 14,
        headerBg: 'transparent',
      },
      Modal: {
        borderRadiusLG: 12,
        titleFontSize: 16,
      },
      Tabs: {
        titleFontSize: 13,
        horizontalItemGutter: 24,
      },
      Tooltip: {
        colorBgSpotlight: dark ? p.bgActive : p.fg,
      },
      Segmented: {
        trackBg: p.bgHover,
      },
      Tag: {
        defaultBg: p.bgHover,
      },
    },
  };
}

export const lightTheme: ThemeConfig = buildTheme(lightPalette, false);
export const darkTheme: ThemeConfig = buildTheme(darkPalette, true);
