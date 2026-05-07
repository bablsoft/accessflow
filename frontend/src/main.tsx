import './i18n';
import { StrictMode, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import enUS from 'antd/locale/en_US';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import dayjs from 'dayjs';
import 'dayjs/locale/en';
import { App } from './App';
import { BootGate } from './components/common/BootGate';
import { RealtimeBridge } from './realtime/RealtimeBridge';
import { darkTheme, lightTheme } from './theme/antdTheme';
import { usePreferencesStore } from './store/preferencesStore';
import './styles/globals.css';

dayjs.locale('en');

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: false },
  },
});

function ThemedApp() {
  const theme = usePreferencesStore((s) => s.theme);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
  return (
    <ConfigProvider theme={theme === 'dark' ? darkTheme : lightTheme} locale={enUS}>
      <BootGate>
        <RealtimeBridge />
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
