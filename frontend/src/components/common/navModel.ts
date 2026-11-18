import type { ReactNode } from 'react';
import type { AuthUser } from '@/api/auth';
import { hasAnyPermission, type Permission } from '@/utils/permissions';
import { governanceDomainsOf, type GovernanceDomain } from '@/hooks/useGovernanceDomains';

export interface NavItem {
  id: string;
  to: string;
  label: string;
  icon: ReactNode;
  /** Visible when the user holds ANY of these permissions (AF-522). */
  permissions: Permission[];
  /** When true, visible to platform admins regardless of role (AF-456). */
  platformAdmin?: boolean;
  badge?: 'pending';
}

/** A collapsible sub-section inside a group (AF-837). `id` is what `preferencesStore` persists. */
export interface NavSubGroup {
  id: string;
  label: string;
  items: NavItem[];
  /**
   * The governance domain this sub-section belongs to (#926). Absent means the always-on
   * database domain. A sub-section whose domain the organization switched off is not offered —
   * but its routes stay registered and its permissions untouched, so every deep link into it
   * (a notification, a bookmark, a dashboard tile) still works.
   */
  domain?: GovernanceDomain;
}

export interface NavGroup {
  id: string;
  /** Absent ⇒ no divider heading (the top generic group). */
  label?: string;
  /** Rendered flat, directly under the group header — above any `subgroups`. */
  items?: NavItem[];
  subgroups?: NavSubGroup[];
}

export interface VisibleNavGroup extends NavGroup {
  items: NavItem[];
  subgroups: NavSubGroup[];
}

/** One destination with the headings it sits under, for breadcrumbs and the command palette. */
export interface NavEntry {
  item: NavItem;
  groupLabel?: string;
  subLabel?: string;
}

/**
 * Drops items the user cannot see, then sub-sections left empty or switched off by governance
 * domain, then groups left with nothing. Visibility only — it grants nothing.
 */
export function visibleNavGroups(groups: NavGroup[], user: AuthUser): VisibleNavGroup[] {
  const domains = governanceDomainsOf(user);
  const canSee = (it: NavItem) =>
    hasAnyPermission(user, it.permissions) || Boolean(it.platformAdmin && user.platform_admin);
  return groups
    .map((g) => ({
      ...g,
      items: (g.items ?? []).filter(canSee),
      subgroups: (g.subgroups ?? [])
        .filter((s) => s.domain === undefined || domains[s.domain])
        .map((s) => ({ ...s, items: s.items.filter(canSee) }))
        .filter((s) => s.items.length > 0),
    }))
    .filter((g) => g.items.length > 0 || g.subgroups.length > 0);
}

export function flattenNav(groups: VisibleNavGroup[]): NavEntry[] {
  return groups.flatMap((g) => [
    ...g.items.map((item) => ({ item, groupLabel: g.label })),
    ...g.subgroups.flatMap((s) =>
      s.items.map((item) => ({ item, groupLabel: g.label, subLabel: s.label })),
    ),
  ]);
}

/**
 * The most specific destination matching `pathname`. Nav paths nest — `/reviews` under
 * `/reviews/attestations` — and a plain prefix test would match the parent alongside the page
 * the user is actually on.
 */
export function activeNavEntry(entries: NavEntry[], pathname: string): NavEntry | null {
  const matches = (to: string) => pathname === to || (to !== '/' && pathname.startsWith(`${to}/`));
  return entries
    .filter((e) => matches(e.item.to))
    .reduce<NavEntry | null>(
      (best, e) => (best === null || e.item.to.length > best.item.to.length ? e : best),
      null,
    );
}
