import { beforeEach, describe, expect, it } from 'vitest';
import {
  DASHBOARD_WIDGET_IDS,
  DEFAULT_WIDGET_SIZE,
  migratePreferences,
  usePreferencesStore,
  widgetSize,
  type DashboardWidgetPreferences,
} from './preferencesStore';

function reset() {
  usePreferencesStore.setState({
    theme: 'light',
    sidebarCollapsed: false,
    navExpandedSubgroups: [],
    setupProgressCollapsed: false,
    setupProgressSkipped: [],
    language: 'en',
    dashboardWidgets: {
      hidden: [],
      order: [...DASHBOARD_WIDGET_IDS],
      collapsed: {},
      size: {},
    },
    dashboardTrendsRange: '30d',
  });
}

describe('preferencesStore base actions', () => {
  beforeEach(reset);

  it('setTheme updates the theme', () => {
    usePreferencesStore.getState().setTheme('dark');
    expect(usePreferencesStore.getState().theme).toBe('dark');
  });

  it('toggleNavSubgroup adds then removes a sidebar sub-section id (AF-837)', () => {
    usePreferencesStore.getState().toggleNavSubgroup('security-identity');
    expect(usePreferencesStore.getState().navExpandedSubgroups).toEqual(['security-identity']);
    usePreferencesStore.getState().toggleNavSubgroup('workflow-api');
    expect(usePreferencesStore.getState().navExpandedSubgroups)
      .toEqual(['security-identity', 'workflow-api']);
    usePreferencesStore.getState().toggleNavSubgroup('security-identity');
    expect(usePreferencesStore.getState().navExpandedSubgroups).toEqual(['workflow-api']);
  });

  it('starts with every sidebar sub-section collapsed', () => {
    expect(usePreferencesStore.getState().navExpandedSubgroups).toEqual([]);
  });

  it('toggleSidebar flips collapsed state', () => {
    usePreferencesStore.getState().toggleSidebar();
    expect(usePreferencesStore.getState().sidebarCollapsed).toBe(true);
    usePreferencesStore.getState().toggleSidebar();
    expect(usePreferencesStore.getState().sidebarCollapsed).toBe(false);
  });

  it('toggleSetupProgress flips collapsed state', () => {
    usePreferencesStore.getState().toggleSetupProgress();
    expect(usePreferencesStore.getState().setupProgressCollapsed).toBe(true);
  });

  it('skipSetupStep adds once and unskip removes', () => {
    usePreferencesStore.getState().skipSetupStep('datasources');
    usePreferencesStore.getState().skipSetupStep('datasources'); // idempotent
    expect(usePreferencesStore.getState().setupProgressSkipped).toEqual(['datasources']);
    usePreferencesStore.getState().unskipSetupStep('datasources');
    expect(usePreferencesStore.getState().setupProgressSkipped).toEqual([]);
  });

  it('setLanguage accepts a supported code and falls back for unsupported', () => {
    usePreferencesStore.getState().setLanguage('de');
    expect(usePreferencesStore.getState().language).toBe('de');
    usePreferencesStore.getState().setLanguage('xx');
    expect(usePreferencesStore.getState().language).toBe('en');
  });

  it('setLanguage is a no-op when unchanged', () => {
    usePreferencesStore.getState().setLanguage('en');
    expect(usePreferencesStore.getState().language).toBe('en');
  });
});

describe('preferencesStore dashboard widgets', () => {
  beforeEach(reset);

  it('defaults to nothing hidden, natural order, no size overrides', () => {
    const { dashboardWidgets, dashboardTrendsRange } = usePreferencesStore.getState();
    expect(dashboardWidgets.hidden).toEqual([]);
    expect(dashboardWidgets.order).toEqual(DASHBOARD_WIDGET_IDS);
    expect(dashboardWidgets.collapsed).toEqual({});
    expect(dashboardWidgets.size).toEqual({});
    expect(dashboardTrendsRange).toBe('30d');
  });

  it('toggleWidgetVisibility hides then re-shows a widget', () => {
    usePreferencesStore.getState().toggleWidgetVisibility('trends');
    expect(usePreferencesStore.getState().dashboardWidgets.hidden).toContain('trends');
    usePreferencesStore.getState().toggleWidgetVisibility('trends');
    expect(usePreferencesStore.getState().dashboardWidgets.hidden).not.toContain('trends');
  });

  it('toggleWidgetCollapsed flips per-widget collapsed state', () => {
    usePreferencesStore.getState().toggleWidgetCollapsed('suggestions');
    expect(usePreferencesStore.getState().dashboardWidgets.collapsed.suggestions).toBe(true);
    usePreferencesStore.getState().toggleWidgetCollapsed('suggestions');
    expect(usePreferencesStore.getState().dashboardWidgets.collapsed.suggestions).toBe(false);
  });

  it('reorderWidgets replaces the order', () => {
    const reversed = [...DASHBOARD_WIDGET_IDS].reverse();
    usePreferencesStore.getState().reorderWidgets(reversed);
    expect(usePreferencesStore.getState().dashboardWidgets.order).toEqual(reversed);
  });

  it('setWidgetSize overrides the default and widgetSize resolves it', () => {
    const before = usePreferencesStore.getState().dashboardWidgets;
    expect(widgetSize(before, 'trends')).toBe(DEFAULT_WIDGET_SIZE.trends);
    usePreferencesStore.getState().setWidgetSize('trends', 'full');
    const after = usePreferencesStore.getState().dashboardWidgets;
    expect(after.size.trends).toBe('full');
    expect(widgetSize(after, 'trends')).toBe('full');
  });

  it('resetDashboardWidgets restores the defaults', () => {
    usePreferencesStore.getState().toggleWidgetVisibility('anomalies');
    usePreferencesStore.getState().toggleWidgetCollapsed('trends');
    usePreferencesStore.getState().setWidgetSize('recentQueries', 'full');
    usePreferencesStore.getState().reorderWidgets([...DASHBOARD_WIDGET_IDS].reverse());
    usePreferencesStore.getState().resetDashboardWidgets();
    const { dashboardWidgets } = usePreferencesStore.getState();
    expect(dashboardWidgets).toEqual({
      hidden: [],
      order: DASHBOARD_WIDGET_IDS,
      collapsed: {},
      size: {},
    });
  });

  it('setDashboardTrendsRange persists the range preference', () => {
    usePreferencesStore.getState().setDashboardTrendsRange('7d');
    expect(usePreferencesStore.getState().dashboardTrendsRange).toBe('7d');
  });

  it('keeps other widget prefs intact when toggling one', () => {
    usePreferencesStore.getState().toggleWidgetCollapsed('anomalies');
    usePreferencesStore.getState().toggleWidgetVisibility('pendingApprovals');
    const { dashboardWidgets } = usePreferencesStore.getState();
    expect(dashboardWidgets.collapsed.anomalies).toBe(true);
    expect(dashboardWidgets.hidden).toContain('pendingApprovals');
    expect(dashboardWidgets.hidden).not.toContain('anomalies');
  });
});

describe('migratePreferences', () => {
  it('turns the visible allow-list into a hidden deny-list', () => {
    const migrated = migratePreferences(
      {
        theme: 'dark',
        dashboardWidgets: {
          visible: ['recentQueries', 'trends'],
          order: ['recentQueries', 'trends', 'suggestions', 'anomalies'],
          collapsed: { trends: true },
        },
      },
      0,
    ) as { theme: string; dashboardWidgets: DashboardWidgetPreferences };
    expect(migrated.dashboardWidgets.hidden).toEqual(['suggestions', 'anomalies']);
    expect(migrated.dashboardWidgets.order).toEqual([
      'recentQueries',
      'trends',
      'suggestions',
      'anomalies',
    ]);
    expect(migrated.dashboardWidgets.collapsed).toEqual({ trends: true });
    expect(migrated.dashboardWidgets.size).toEqual({});
    expect(migrated.theme).toBe('dark');
  });

  it('does not hide a widget absent from the persisted order (the v0 visibility bug)', () => {
    // Under v0, a widget missing from `order` was always shown regardless of `visible` — so its
    // absence from `visible` is not evidence the user hid it.
    const migrated = migratePreferences(
      {
        dashboardWidgets: {
          visible: ['recentQueries'],
          order: ['recentQueries', 'trends'],
          collapsed: {},
        },
      },
      0,
    ) as { dashboardWidgets: DashboardWidgetPreferences };
    expect(migrated.dashboardWidgets.hidden).toEqual(['trends']);
    expect(migrated.dashboardWidgets.hidden).not.toContain('suggestions');
  });

  it('falls back to defaults when dashboardWidgets is missing', () => {
    const migrated = migratePreferences({ theme: 'light' }, 0) as {
      dashboardWidgets: DashboardWidgetPreferences;
    };
    expect(migrated.dashboardWidgets.hidden).toEqual([]);
    expect(migrated.dashboardWidgets.order).toEqual(DASHBOARD_WIDGET_IDS);
  });

  it('tolerates a partially-missing v0 payload', () => {
    const migrated = migratePreferences({ dashboardWidgets: {} }, 0) as {
      dashboardWidgets: DashboardWidgetPreferences;
    };
    expect(migrated.dashboardWidgets.hidden).toEqual([]);
    expect(migrated.dashboardWidgets.order).toEqual(DASHBOARD_WIDGET_IDS);
    expect(migrated.dashboardWidgets.size).toEqual({});
  });

  it('v1 → v2 drops the retired navCollapsedSubgroups deny-list', () => {
    // The deny-list is not inverted into the allow-list: the nav now starts fully collapsed for
    // everyone, so a user who had left three sections open gets them closed on the next load.
    const v1 = {
      theme: 'dark',
      sidebarCollapsed: true,
      navCollapsedSubgroups: ['security-identity'],
    };
    const migrated = migratePreferences(v1, 1) as Record<string, unknown>;
    expect(migrated).not.toBe(v1);
    expect(migrated.navCollapsedSubgroups).toBeUndefined();
    expect(migrated.theme).toBe('dark');

    // The new key is absent too, and zustand's shallow merge over the initial state supplies `[]`.
    // This pins that — a `partialize`/custom `merge` turning it into `undefined` would make
    // Sidebar's `.includes()` throw on the first render.
    expect(migrated.navExpandedSubgroups).toBeUndefined();
    const hydrated = { ...usePreferencesStore.getState(), ...migrated };
    expect(hydrated.navExpandedSubgroups).toEqual([]);
  });

  it('carries a v0 payload through both steps', () => {
    const v0 = {
      navCollapsedSubgroups: ['workflow-api'],
      dashboardWidgets: { visible: ['trends'], order: ['trends', 'riskMix'] },
    };
    const migrated = migratePreferences(v0, 0) as {
      navCollapsedSubgroups?: unknown;
      dashboardWidgets: DashboardWidgetPreferences;
    };
    expect(migrated.navCollapsedSubgroups).toBeUndefined();
    expect(migrated.dashboardWidgets.hidden).toEqual(['riskMix']);
  });

  it('passes v2 state and non-object payloads through unchanged', () => {
    const v2 = { dashboardWidgets: { hidden: [], order: [], collapsed: {}, size: {} } };
    expect(migratePreferences(v2, 2)).toBe(v2);
    expect(migratePreferences(null, 0)).toBeNull();
  });
});
