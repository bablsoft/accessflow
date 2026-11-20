import './i18n';
import { StrictMode, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider, createBrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import enUS from 'antd/locale/en_US';
import esES from 'antd/locale/es_ES';
import deDE from 'antd/locale/de_DE';
import frFR from 'antd/locale/fr_FR';
import zhCN from 'antd/locale/zh_CN';
import ruRU from 'antd/locale/ru_RU';
import hyAM from 'antd/locale/hy_AM';
import type { Locale } from 'antd/lib/locale';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import dayjs from 'dayjs';
import 'dayjs/locale/en';
import 'dayjs/locale/es';
import 'dayjs/locale/de';
import 'dayjs/locale/fr';
import 'dayjs/locale/zh-cn';
import 'dayjs/locale/ru';
import 'dayjs/locale/hy-am';
import { App } from './App';
import { BootGate } from './components/common/BootGate';
import { darkTheme, lightTheme } from './theme/antdTheme';
import { darkPalette, lightPalette } from './theme/palette';
import { MotionProvider } from './motion/MotionProvider';
import { usePreferencesStore } from './store/preferencesStore';
import type { Language } from './i18n';
import '@fontsource-variable/geist';
import '@fontsource-variable/geist-mono';
import './styles/globals.css';
import './styles/utilities.css';
import './styles/bklit.css';

const ANTD_LOCALES: Record<Language, Locale> = {
  en: enUS,
  es: esES,
  de: deDE,
  fr: frFR,
  'zh-CN': zhCN,
  ru: ruRU,
  hy: hyAM,
};

const DAYJS_LOCALES: Record<Language, string> = {
  en: 'en',
  es: 'es',
  de: 'de',
  fr: 'fr',
  'zh-CN': 'zh-cn',
  ru: 'ru',
  hy: 'hy-am',
};

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: false },
  },
});

function ThemedApp() {
  const theme = usePreferencesStore((s) => s.theme);
  const language = usePreferencesStore((s) => s.language);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document
      .querySelector('meta[name="theme-color"]')
      ?.setAttribute('content', (theme === 'dark' ? darkPalette : lightPalette).bg);
  }, [theme]);

  useEffect(() => {
    dayjs.locale(DAYJS_LOCALES[language] ?? 'en');
    document.documentElement.lang = language;
  }, [language]);

  return (
    <ConfigProvider
      theme={theme === 'dark' ? darkTheme : lightTheme}
      locale={ANTD_LOCALES[language] ?? enUS}
    >
      <MotionProvider>
        <BootGate>
          <App />
        </BootGate>
      </MotionProvider>
    </ConfigProvider>
  );
}

// A data router with one catch-all route: the route table stays the `<Routes>` tree in App.tsx,
// and data-router-only hooks (`useBlocker`, behind UnsavedChangesGuard) become available.
const router = createBrowserRouter([{ path: '*', element: <ThemedApp /> }]);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);

// PWA service worker (AF-444): register manually (not via an inline script) so the strict CSP is
// honoured. Production only — the dev server has its own module graph. Enables the offline review
// shell and Web Push delivery.
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js').catch(() => {
      /* registration is best-effort; the app works without it */
    });
  });
}
