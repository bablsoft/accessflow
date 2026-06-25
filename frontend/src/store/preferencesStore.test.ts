import { beforeEach, describe, expect, it } from 'vitest';
import { DASHBOARD_WIDGET_IDS, usePreferencesStore } from './preferencesStore';

function reset() {
  usePreferencesStore.setState({
    theme: 'light',
    sidebarCollapsed: false,
    setupProgressCollapsed: false,
    setupProgressSkipped: [],
    language: 'en',
    dashboardWidgets: {
      visible: [...DASHBOARD_WIDGET_IDS],
      order: [...DASHBOARD_WIDGET_IDS],
      collapsed: {},
    },
  });
}

describe('preferencesStore base actions', () => {
  beforeEach(reset);

  it('setTheme updates the theme', () => {
    usePreferencesStore.getState().setTheme('dark');
    expect(usePreferencesStore.getState().theme).toBe('dark');
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

  it('defaults to all widgets visible in natural order', () => {
    const { dashboardWidgets } = usePreferencesStore.getState();
    expect(dashboardWidgets.visible).toEqual(DASHBOARD_WIDGET_IDS);
    expect(dashboardWidgets.order).toEqual(DASHBOARD_WIDGET_IDS);
    expect(dashboardWidgets.collapsed).toEqual({});
  });

  it('toggleWidgetVisibility removes then re-adds a widget', () => {
    usePreferencesStore.getState().toggleWidgetVisibility('trends');
    expect(usePreferencesStore.getState().dashboardWidgets.visible).not.toContain('trends');
    usePreferencesStore.getState().toggleWidgetVisibility('trends');
    expect(usePreferencesStore.getState().dashboardWidgets.visible).toContain('trends');
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

  it('keeps other widget prefs intact when toggling one', () => {
    usePreferencesStore.getState().toggleWidgetCollapsed('anomalies');
    usePreferencesStore.getState().toggleWidgetVisibility('pendingApprovals');
    const { dashboardWidgets } = usePreferencesStore.getState();
    expect(dashboardWidgets.collapsed.anomalies).toBe(true);
    expect(dashboardWidgets.visible).not.toContain('pendingApprovals');
    expect(dashboardWidgets.visible).toContain('anomalies');
  });
});
