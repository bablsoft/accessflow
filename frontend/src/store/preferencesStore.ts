import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import i18n, { isSupportedLanguage, type Language } from '@/i18n';

export type ThemeMode = 'light' | 'dark';

export type SetupStepId = 'review_plans' | 'datasources' | 'ai_provider';

/** The customizable widgets on the personalized dashboard (AF-498), in their natural order. */
export type DashboardWidgetId =
  | 'pendingApprovals'
  | 'recentQueries'
  | 'trends'
  | 'suggestions'
  | 'anomalies';

export const DASHBOARD_WIDGET_IDS: DashboardWidgetId[] = [
  'pendingApprovals',
  'recentQueries',
  'trends',
  'suggestions',
  'anomalies',
];

export interface DashboardWidgetPreferences {
  /** Widget ids the user has chosen to show. */
  visible: DashboardWidgetId[];
  /** Widget ids in the user's preferred order. */
  order: DashboardWidgetId[];
  /** Per-widget collapsed state. */
  collapsed: Partial<Record<DashboardWidgetId, boolean>>;
}

const defaultDashboardWidgets = (): DashboardWidgetPreferences => ({
  visible: [...DASHBOARD_WIDGET_IDS],
  order: [...DASHBOARD_WIDGET_IDS],
  collapsed: {},
});

interface PreferencesState {
  theme: ThemeMode;
  sidebarCollapsed: boolean;
  setupProgressCollapsed: boolean;
  setupProgressSkipped: SetupStepId[];
  language: Language;
  dashboardWidgets: DashboardWidgetPreferences;
  setTheme: (t: ThemeMode) => void;
  toggleSidebar: () => void;
  toggleSetupProgress: () => void;
  skipSetupStep: (id: SetupStepId) => void;
  unskipSetupStep: (id: SetupStepId) => void;
  setLanguage: (code: string | null | undefined) => void;
  toggleWidgetVisibility: (id: DashboardWidgetId) => void;
  toggleWidgetCollapsed: (id: DashboardWidgetId) => void;
  reorderWidgets: (order: DashboardWidgetId[]) => void;
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
      dashboardWidgets: defaultDashboardWidgets(),
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
      toggleWidgetVisibility: (id) =>
        set((s) => {
          const visible = s.dashboardWidgets.visible.includes(id)
            ? s.dashboardWidgets.visible.filter((w) => w !== id)
            : [...s.dashboardWidgets.visible, id];
          return { dashboardWidgets: { ...s.dashboardWidgets, visible } };
        }),
      toggleWidgetCollapsed: (id) =>
        set((s) => ({
          dashboardWidgets: {
            ...s.dashboardWidgets,
            collapsed: {
              ...s.dashboardWidgets.collapsed,
              [id]: !s.dashboardWidgets.collapsed[id],
            },
          },
        })),
      reorderWidgets: (order) =>
        set((s) => ({ dashboardWidgets: { ...s.dashboardWidgets, order } })),
    }),
    { name: 'af-preferences' },
  ),
);
