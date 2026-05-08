import { theme, type ThemeConfig } from 'antd';

const fontFamily =
  "'Geist', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
const fontFamilyCode = "'Geist Mono', 'SF Mono', Menlo, Consolas, monospace";

const sharedToken = {
  fontFamily,
  fontFamilyCode,
  fontSize: 13,
  borderRadius: 6,
  borderRadiusSM: 4,
  borderRadiusLG: 8,
  controlHeight: 32,
  controlHeightSM: 26,
};

export const lightTheme: ThemeConfig = {
  algorithm: theme.defaultAlgorithm,
  cssVar: { key: 'af' },
  hashed: false,
  token: {
    ...sharedToken,
    colorPrimary: '#6366f1',
    colorInfo: '#2563eb',
    colorSuccess: '#16a34a',
    colorWarning: '#ca8a04',
    colorError: '#dc2626',
    colorBgBase: '#fafafa',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#ffffff',
    colorBgLayout: '#fafafa',
    colorBorder: '#e7e7ea',
    colorBorderSecondary: '#ececef',
    colorText: '#18181b',
    colorTextSecondary: '#52525b',
    colorTextTertiary: '#71717a',
    colorTextQuaternary: '#a1a1aa',
  },
  components: {
    Layout: {
      bodyBg: '#fafafa',
      headerBg: '#ffffff',
      siderBg: '#ffffff',
    },
    Menu: {
      itemBg: 'transparent',
      itemSelectedBg: '#ececef',
      itemHoverBg: '#f4f4f5',
      itemSelectedColor: '#18181b',
      itemColor: '#52525b',
    },
  },
};

export const darkTheme: ThemeConfig = {
  algorithm: theme.darkAlgorithm,
  cssVar: { key: 'af' },
  hashed: false,
  token: {
    ...sharedToken,
    colorPrimary: '#818cf8',
    colorInfo: '#60a5fa',
    colorSuccess: '#4ade80',
    colorWarning: '#fbbf24',
    colorError: '#f87171',
    colorBgBase: '#09090b',
    colorBgContainer: '#131316',
    colorBgElevated: '#131316',
    colorBgLayout: '#09090b',
    colorBorder: '#232328',
    colorBorderSecondary: '#1c1c20',
    colorText: '#fafafa',
    colorTextSecondary: '#d4d4d8',
    colorTextTertiary: '#a1a1aa',
    colorTextQuaternary: '#8b8b94',
  },
  components: {
    Layout: {
      bodyBg: '#09090b',
      headerBg: '#131316',
      siderBg: '#0c0c0e',
    },
    Menu: {
      itemBg: 'transparent',
      itemSelectedBg: '#232328',
      itemHoverBg: '#1c1c20',
      itemSelectedColor: '#fafafa',
      itemColor: '#d4d4d8',
    },
  },
};
