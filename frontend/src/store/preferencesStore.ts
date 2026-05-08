import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { Edition } from '@/types/api';

export type ThemeMode = 'light' | 'dark';

interface PreferencesState {
  theme: ThemeMode;
  sidebarCollapsed: boolean;
  edition: Edition;
  setTheme: (t: ThemeMode) => void;
  toggleSidebar: () => void;
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
    (set) => ({
      theme: initialTheme(),
      sidebarCollapsed: false,
      edition: initialEdition(),
      setTheme: (theme) => set({ theme }),
      toggleSidebar: () =>
        set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
    }),
    { name: 'af-preferences' },
  ),
);
