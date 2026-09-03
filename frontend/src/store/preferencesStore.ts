import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import i18n, { isSupportedLanguage, type Language } from '@/i18n';

export type ThemeMode = 'light' | 'dark';

export type SetupStepId = 'review_plans' | 'datasources' | 'ai_provider';

/** The customizable widgets on the personalized dashboard (AF-498), in their natural order. */
export type DashboardWidgetId =
  | 'pendingApprovals'
  | 'attestationsDue'
  | 'recentQueries'
  | 'myAccessRequests'
  | 'myRequestGroups'
  | 'trends'
  | 'riskMix'
  | 'activityHeatmap'
  | 'suggestions'
  | 'anomalies'
  | 'recentApiRequests'
  | 'apiRequestTrends'
  | 'pendingApiApprovals';

export const DASHBOARD_WIDGET_IDS: DashboardWidgetId[] = [
  'pendingApprovals',
  'attestationsDue',
  'recentQueries',
  'myAccessRequests',
  'myRequestGroups',
  'trends',
  'riskMix',
  'activityHeatmap',
  'suggestions',
  'anomalies',
  'recentApiRequests',
  'apiRequestTrends',
  'pendingApiApprovals',
];

export type DashboardWidgetSize = 'half' | 'full';

/** Grid span each widget gets until the user overrides it (suggestions carry long rationale text). */
export const DEFAULT_WIDGET_SIZE: Record<DashboardWidgetId, DashboardWidgetSize> = {
  pendingApprovals: 'half',
  attestationsDue: 'half',
  recentQueries: 'half',
  myAccessRequests: 'half',
  myRequestGroups: 'half',
  trends: 'half',
  riskMix: 'half',
  activityHeatmap: 'half',
  suggestions: 'full',
  anomalies: 'half',
  recentApiRequests: 'half',
  apiRequestTrends: 'half',
  pendingApiApprovals: 'half',
};

export type DashboardTrendsRange = '7d' | '30d' | '90d';

export interface DashboardWidgetPreferences {
  /**
   * Widget ids the user has explicitly hidden. Anything not listed is visible, so a widget that
   * ships after the prefs were first persisted appears (and can be hidden) without a migration.
   */
  hidden: DashboardWidgetId[];
  /** Widget ids in the user's preferred order. */
  order: DashboardWidgetId[];
  /** Per-widget collapsed state. */
  collapsed: Partial<Record<DashboardWidgetId, boolean>>;
  /** Per-widget grid-span override; absent means the widget's default size. */
  size: Partial<Record<DashboardWidgetId, DashboardWidgetSize>>;
}

const defaultDashboardWidgets = (): DashboardWidgetPreferences => ({
  hidden: [],
  order: [...DASHBOARD_WIDGET_IDS],
  collapsed: {},
  size: {},
});

/** Effective grid span for a widget: the user's override, else the widget default. */
export function widgetSize(
  prefs: DashboardWidgetPreferences,
  id: DashboardWidgetId,
): DashboardWidgetSize {
  return prefs.size[id] ?? DEFAULT_WIDGET_SIZE[id];
}

/** The pre-v1 persisted widget prefs (`visible[]` allow-list instead of `hidden[]`). */
interface DashboardWidgetPreferencesV0 {
  visible?: DashboardWidgetId[];
  order?: DashboardWidgetId[];
  collapsed?: Partial<Record<DashboardWidgetId, boolean>>;
}

interface PreferencesState {
  theme: ThemeMode;
  sidebarCollapsed: boolean;
  /**
   * Ids of sidebar sub-sections the user has expanded (AF-837). An allow-list, so every
   * sub-section — including one that ships after the prefs were first persisted — starts
   * collapsed, and a fresh login shows the nav fully closed. The key's absence from an older
   * `af-preferences` payload falls back to `[]` via zustand's shallow merge over the initial
   * state. Nothing prunes the list: an id dropped in a future nav refactor simply stops
   * matching, so a retired id lingering in a user's `af-preferences` is inert.
   */
  navExpandedSubgroups: string[];
  setupProgressCollapsed: boolean;
  setupProgressSkipped: SetupStepId[];
  language: Language;
  dashboardWidgets: DashboardWidgetPreferences;
  dashboardTrendsRange: DashboardTrendsRange;
  setTheme: (t: ThemeMode) => void;
  toggleSidebar: () => void;
  toggleNavSubgroup: (id: string) => void;
  toggleSetupProgress: () => void;
  skipSetupStep: (id: SetupStepId) => void;
  unskipSetupStep: (id: SetupStepId) => void;
  setLanguage: (code: string | null | undefined) => void;
  toggleWidgetVisibility: (id: DashboardWidgetId) => void;
  toggleWidgetCollapsed: (id: DashboardWidgetId) => void;
  reorderWidgets: (order: DashboardWidgetId[]) => void;
  setWidgetSize: (id: DashboardWidgetId, size: DashboardWidgetSize) => void;
  resetDashboardWidgets: () => void;
  setDashboardTrendsRange: (range: DashboardTrendsRange) => void;
}

const initialTheme = (): ThemeMode => {
  if (typeof window === 'undefined') return 'light';
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
};

type PersistedPreferences = Record<string, unknown> & {
  dashboardWidgets?: DashboardWidgetPreferencesV0;
};

/**
 * v0 → v1: `visible[]` (allow-list) becomes `hidden[]` (deny-list). Only widgets the user's
 * persisted `order` knew about can have been deliberately hidden — under v0, an id absent from
 * `order` was always shown regardless of `visible`, so it must not migrate to hidden.
 */
function migrateWidgetsToV1(state: PersistedPreferences): PersistedPreferences {
  const v0 = state.dashboardWidgets;
  if (!v0) {
    return { ...state, dashboardWidgets: defaultDashboardWidgets() };
  }
  const visible = v0.visible ?? [...DASHBOARD_WIDGET_IDS];
  const order = v0.order ?? [...DASHBOARD_WIDGET_IDS];
  const migrated: DashboardWidgetPreferences = {
    hidden: order.filter((id) => !visible.includes(id)),
    order,
    collapsed: v0.collapsed ?? {},
    size: {},
  };
  return { ...state, dashboardWidgets: migrated };
}

/**
 * v1 → v2: the sidebar sub-section preference flips from `navCollapsedSubgroups` (a deny-list,
 * everything open until closed) to `navExpandedSubgroups` (an allow-list, everything closed
 * until opened). Nothing carries over — the nav now starts fully collapsed for everyone — and
 * the stale key is dropped so it cannot linger in `af-preferences` forever.
 */
function migrateNavSubgroupsToV2(state: PersistedPreferences): PersistedPreferences {
  const next = { ...state };
  delete next.navCollapsedSubgroups;
  return next;
}

export function migratePreferences(persisted: unknown, version: number): unknown {
  if (version >= 2 || typeof persisted !== 'object' || persisted === null) {
    return persisted;
  }
  let state = persisted as PersistedPreferences;
  if (version < 1) {
    state = migrateWidgetsToV1(state);
  }
  return migrateNavSubgroupsToV2(state);
}

export const usePreferencesStore = create<PreferencesState>()(
  persist(
    (set, get) => ({
      theme: initialTheme(),
      sidebarCollapsed: false,
      navExpandedSubgroups: [],
      setupProgressCollapsed: false,
      setupProgressSkipped: [],
      language: 'en',
      dashboardWidgets: defaultDashboardWidgets(),
      dashboardTrendsRange: '30d',
      setTheme: (theme) => set({ theme }),
      toggleSidebar: () =>
        set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
      toggleNavSubgroup: (id) =>
        set((s) => ({
          navExpandedSubgroups: s.navExpandedSubgroups.includes(id)
            ? s.navExpandedSubgroups.filter((x) => x !== id)
            : [...s.navExpandedSubgroups, id],
        })),
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
          const hidden = s.dashboardWidgets.hidden.includes(id)
            ? s.dashboardWidgets.hidden.filter((w) => w !== id)
            : [...s.dashboardWidgets.hidden, id];
          return { dashboardWidgets: { ...s.dashboardWidgets, hidden } };
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
      setWidgetSize: (id, size) =>
        set((s) => ({
          dashboardWidgets: {
            ...s.dashboardWidgets,
            size: { ...s.dashboardWidgets.size, [id]: size },
          },
        })),
      resetDashboardWidgets: () => set({ dashboardWidgets: defaultDashboardWidgets() }),
      setDashboardTrendsRange: (range) => set({ dashboardTrendsRange: range }),
    }),
    {
      name: 'af-preferences',
      version: 2,
      // The persisted payload is a plain partial snapshot; migratePreferences is typed `unknown`
      // so tests can feed raw JSON. zustand merges it over the initial state after this cast.
      migrate: (persisted, version) => migratePreferences(persisted, version) as PreferencesState,
    },
  ),
);
