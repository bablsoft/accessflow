import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import i18n, { isSupportedLanguage, type Language } from '@/i18n';
import type { Edition } from '@/types/api';

export type ThemeMode = 'light' | 'dark';

interface PreferencesState {
  theme: ThemeMode;
  sidebarCollapsed: boolean;
  edition: Edition;
  language: Language;
  setTheme: (t: ThemeMode) => void;
  toggleSidebar: () => void;
  setLanguage: (code: string | null | undefined) => void;
}

const initialTheme = (): ThemeMode => {
  if (typeof window === 'undefined') return 'light';
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
};

const initialEdition = (): Edition => {
  const raw = import.meta.env.VITE_APP_EDITION;
  return raw === 'enterprise' ? 'ENTERPRISE' : 'COMMUNITY';
};

export const usePreferencesStore = create<PreferencesState>()(
  persist(
    (set, get) => ({
      theme: initialTheme(),
      sidebarCollapsed: false,
      edition: initialEdition(),
      language: 'en',
      setTheme: (theme) => set({ theme }),
      toggleSidebar: () =>
        set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
      setLanguage: (code) => {
        const next: Language = isSupportedLanguage(code) ? code : 'en';
        if (next === get().language) {
          return;
        }
        set({ language: next });
        if (i18n.language !== next) {
          void i18n.changeLanguage(next);
        }
      },
    }),
    { name: 'af-preferences' },
  ),
);
