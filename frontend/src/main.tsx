import './i18n';
import { StrictMode, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
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
import { usePreferencesStore } from './store/preferencesStore';
import type { Language } from './i18n';
import './styles/globals.css';

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
      <BootGate>
        <App />
      </BootGate>
    </ConfigProvider>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <QueryClientProvider client={queryClient}>
        <ThemedApp />
      </QueryClientProvider>
    </BrowserRouter>
  </StrictMode>,
);
