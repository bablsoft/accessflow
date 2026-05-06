import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type ThemeMode = 'light' | 'dark';

interface PreferencesState {
  theme: ThemeMode;
  sidebarCollapsed: boolean;
  setTheme: (t: ThemeMode) => void;
  toggleSidebar: () => void;
}

const initialTheme = (): ThemeMode => {
  if (typeof window === 'undefined') return 'light';
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
};

export const usePreferencesStore = create<PreferencesState>()(
  persist(
    (set) => ({
      theme: initialTheme(),
      sidebarCollapsed: false,
      setTheme: (theme) => set({ theme }),
      toggleSidebar: () =>
        set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
    }),
    { name: 'af-preferences' },
  ),
);
