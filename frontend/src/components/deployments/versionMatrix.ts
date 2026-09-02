import type { TFunction } from 'i18next';
import type { DeploymentEnvironmentVersion } from '@/types/api';

/**
 * Pure classification of a matrix row's drift and rollback state (#743).
 *
 * The server's drift block carries three nullable quantities whose combinations are not obvious
 * (a non-drifted row short-circuits both counts to 0; a row that was never deployed gets null
 * for both; a pipeline whose every release failed reports `drifted: true` against a null
 * `latest_version`). Classifying once, here, keeps that branch load out of the four components
 * that render the badge.
 */
export type DriftBadgeKind =
  | 'up_to_date'
  | 'never_deployed'
  | 'behind'
  | 'days'
  | 'versions'
  | 'versions_and_days';

export interface DriftBadge {
  kind: DriftBadgeKind;
  /** Normalised — the server's null becomes 0. */
  days: number;
  versions: number;
  latestVersion: string | null;
  drifted: boolean;
}

export function driftBadge(row: DeploymentEnvironmentVersion): DriftBadge {
  const { drift } = row;
  const days = drift.days_behind ?? 0;
  const versions = drift.deployments_behind ?? 0;
  const base = { days, versions, latestVersion: drift.latest_version, drifted: drift.drifted };
  if (!drift.drifted) return { ...base, kind: 'up_to_date' };
  if (row.deployed_at == null) return { ...base, kind: 'never_deployed' };
  if (versions > 0 && days > 0) return { ...base, kind: 'versions_and_days' };
  if (versions > 0) return { ...base, kind: 'versions' };
  if (days > 0) return { ...base, kind: 'days' };
  return { ...base, kind: 'behind' };
}

export function driftBadgeText(t: TFunction, badge: DriftBadge): string {
  switch (badge.kind) {
    case 'up_to_date':
      return t('deploygov.versions.upToDate');
    case 'never_deployed':
      return t('deploygov.versions.driftNeverDeployed');
    case 'versions_and_days':
      // Two counts, one frame: i18next carries a single `count` per key, so a combined string
      // could not pluralise both halves in ru/hy. The fragments do the pluralising.
      return t('deploygov.versions.driftBoth', {
        versions: t('deploygov.versions.driftVersionsCount', { count: badge.versions }),
        days: t('deploygov.versions.driftDaysCount', { count: badge.days }),
      });
    case 'versions':
      return t('deploygov.versions.driftVersions', { count: badge.versions });
    case 'days':
      return t('deploygov.versions.driftDays', { count: badge.days });
    case 'behind':
      return t('deploygov.versions.driftBehind');
  }
}

/** Tooltip copy naming the version the row is compared against. */
export function driftTooltipText(t: TFunction, badge: DriftBadge): string {
  return badge.latestVersion == null
    ? t('deploygov.versions.driftLatestUnknown')
    : t('deploygov.versions.driftLatest', { version: badge.latestVersion });
}

export type RollbackBadge =
  | { kind: 'none' }
  | { kind: 'reverted'; version: string }
  | { kind: 'unknown' };

/**
 * `current_version` already *is* the reverted-to value — the tracker performs a single-level
 * current→previous undo inside the outcome transaction. Two consecutive rollbacks exhaust the
 * previous slot and leave it null, which is the 'unknown' case.
 */
export function rollbackBadge(row: DeploymentEnvironmentVersion): RollbackBadge {
  if (row.last_outcome !== 'ROLLED_BACK' && row.last_outcome !== 'FAILED') return { kind: 'none' };
  if (row.current_version == null) return { kind: 'unknown' };
  return { kind: 'reverted', version: row.current_version };
}

export function rollbackBadgeText(t: TFunction, badge: RollbackBadge): string {
  switch (badge.kind) {
    case 'reverted':
      return t('deploygov.versions.revertedTo', { version: badge.version });
    case 'unknown':
      return t('deploygov.versions.revertedUnknown');
    case 'none':
      return '';
  }
}

/**
 * Tag filter options, built from the tags present on the loaded rows.
 *
 * `tag` is a server-side filter, so once one is selected the response only contains rows carrying
 * it and every other tag drops out of the list until the filter is cleared. `keep` pins the
 * selected value so it never vanishes from its own Select.
 */
export function tagOptions(
  rows: readonly DeploymentEnvironmentVersion[],
  keep?: string | null,
): { value: string; label: string }[] {
  const seen = new Set<string>();
  for (const row of rows) {
    for (const tag of row.environment.tags) seen.add(tag);
  }
  if (keep) seen.add(keep);
  return [...seen]
    .sort((a, b) => a.localeCompare(b))
    .map((tag) => ({ value: tag, label: tag }));
}
