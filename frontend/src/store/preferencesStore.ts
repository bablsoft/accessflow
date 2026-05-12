import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import i18n, { isSupportedLanguage, type Language } from '@/i18n';

export type ThemeMode = 'light' | 'dark';

export type SetupStepId = 'review_plans' | 'datasources' | 'ai_provider';

interface PreferencesState {
  theme: ThemeMode;
  sidebarCollapsed: boolean;
  setupProgressCollapsed: boolean;
  setupProgressSkipped: SetupStepId[];
  language: Language;
  setTheme: (t: ThemeMode) => void;
  toggleSidebar: () => void;
  toggleSetupProgress: () => void;
  skipSetupStep: (id: SetupStepId) => void;
  unskipSetupStep: (id: SetupStepId) => void;
  setLanguage: (code: string | null | undefined) => void;
}

const initialTheme = (): ThemeMode => {
  if (typeof window === 'undefined') return 'light';
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
};

export const usePreferencesStore = create<PreferencesState>()(
  persist(
    (set, get) => ({
      theme: initialTheme(),
      sidebarCollapsed: false,
      setupProgressCollapsed: false,
      setupProgressSkipped: [],
      language: 'en',
      setTheme: (theme) => set({ theme }),
      toggleSidebar: () =>
        set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
      toggleSetupProgress: () =>
        set((s) => ({ setupProgressCollapsed: !s.setupProgressCollapsed })),
      skipSetupStep: (id) =>
        set((s) => (s.setupProgressSkipped.includes(id)
          ? s
          : { setupProgressSkipped: [...s.setupProgressSkipped, id] })),
      unskipSetupStep: (id) =>
        set((s) => ({
          setupProgressSkipped: s.setupProgressSkipped.filter((x) => x !== id),
        })),
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
