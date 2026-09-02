import { beforeAll, describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import type { DeploymentEnvironmentVersion, DeploymentVersionDrift } from '@/types/api';
import {
  driftBadge,
  driftBadgeText,
  driftTooltipText,
  rollbackBadge,
  rollbackBadgeText,
  tagOptions,
} from './versionMatrix';

function makeRow(
  overrides: Partial<DeploymentEnvironmentVersion> = {},
  drift: Partial<DeploymentVersionDrift> = {},
): DeploymentEnvironmentVersion {
  return {
    pipeline_id: 'p-1',
    pipeline_name: 'payments-api',
    environment: { id: 'e-1', name: 'prod', tags: ['prod'], sort_order: 3 },
    current_version: '2.4.0',
    current_request_id: 'r-1',
    deployed_at: '2026-08-20T10:15:00Z',
    previous_version: '2.3.9',
    last_outcome: 'SUCCEEDED',
    ...overrides,
    drift: {
      latest_version: '2.4.1',
      latest_deployed_at: '2026-08-24T10:15:00Z',
      drifted: true,
      days_behind: 4,
      deployments_behind: 1,
      ...drift,
    },
  };
}

let t: TFunction;

beforeAll(async () => {
  const i18n = (await import('@/i18n')).default;
  t = i18n.t.bind(i18n) as TFunction;
});

describe('driftBadge', () => {
  it('classifies an up-to-date row before anything else', () => {
    const badge = driftBadge(
      makeRow({}, { drifted: false, days_behind: 0, deployments_behind: 0 }),
    );
    expect(badge.kind).toBe('up_to_date');
    expect(driftBadgeText(t, badge)).toBe('Up to date');
  });

  it('classifies a never-deployed environment', () => {
    const badge = driftBadge(
      makeRow(
        { current_version: null, current_request_id: null, deployed_at: null },
        { days_behind: null, deployments_behind: null },
      ),
    );
    expect(badge.kind).toBe('never_deployed');
    expect(badge.days).toBe(0);
    expect(badge.versions).toBe(0);
    expect(driftBadgeText(t, badge)).toBe('Never deployed');
  });

  it('combines both quantities when both are positive', () => {
    const badge = driftBadge(makeRow({}, { days_behind: 4, deployments_behind: 2 }));
    expect(badge.kind).toBe('versions_and_days');
    expect(driftBadgeText(t, badge)).toBe('2 versions / 4 days behind');
  });

  it('pluralises the combined frame down to one of each', () => {
    const badge = driftBadge(makeRow({}, { days_behind: 1, deployments_behind: 1 }));
    expect(driftBadgeText(t, badge)).toBe('1 version / 1 day behind');
  });

  it('reports versions alone when the day count is zero', () => {
    const badge = driftBadge(makeRow({}, { days_behind: 0, deployments_behind: 3 }));
    expect(badge.kind).toBe('versions');
    expect(driftBadgeText(t, badge)).toBe('3 versions behind');
  });

  it('reports versions alone when the day count is null', () => {
    const badge = driftBadge(makeRow({}, { days_behind: null, deployments_behind: 3 }));
    expect(badge.kind).toBe('versions');
  });

  it('reports days alone when the version count is null', () => {
    const badge = driftBadge(makeRow({}, { days_behind: 5, deployments_behind: null }));
    expect(badge.kind).toBe('days');
    expect(driftBadgeText(t, badge)).toBe('5 days behind');
  });

  it('pluralises a single day', () => {
    const badge = driftBadge(makeRow({}, { days_behind: 1, deployments_behind: 0 }));
    expect(driftBadgeText(t, badge)).toBe('1 day behind');
  });

  it('falls back to a bare "behind" when drifted with no measurable lag', () => {
    const badge = driftBadge(makeRow({}, { days_behind: 0, deployments_behind: 0 }));
    expect(badge.kind).toBe('behind');
    expect(driftBadgeText(t, badge)).toBe('Behind latest');
  });
});

describe('driftTooltipText', () => {
  it('names the latest version when one is known', () => {
    const badge = driftBadge(makeRow());
    expect(driftTooltipText(t, badge)).toBe('Latest: 2.4.1');
  });

  it('explains the conservative flag when no release qualifies as latest', () => {
    const badge = driftBadge(makeRow({}, { latest_version: null, latest_deployed_at: null }));
    expect(badge.latestVersion).toBeNull();
    expect(driftTooltipText(t, badge)).toContain('unknown');
  });
});

describe('rollbackBadge', () => {
  it('stays silent on a successful or unreported outcome', () => {
    expect(rollbackBadge(makeRow({ last_outcome: 'SUCCEEDED' }))).toEqual({ kind: 'none' });
    expect(rollbackBadge(makeRow({ last_outcome: null }))).toEqual({ kind: 'none' });
    expect(rollbackBadgeText(t, { kind: 'none' })).toBe('');
  });

  it('names the version a rollback reverted to', () => {
    const badge = rollbackBadge(makeRow({ last_outcome: 'ROLLED_BACK', current_version: '2.3.9' }));
    expect(badge).toEqual({ kind: 'reverted', version: '2.3.9' });
    expect(rollbackBadgeText(t, badge)).toBe('reverted to 2.3.9');
  });

  it('fires on a failed outcome too', () => {
    expect(rollbackBadge(makeRow({ last_outcome: 'FAILED' })).kind).toBe('reverted');
  });

  it('admits it does not know when consecutive rollbacks exhausted the previous slot', () => {
    const badge = rollbackBadge(
      makeRow({ last_outcome: 'ROLLED_BACK', current_version: null }),
    );
    expect(badge).toEqual({ kind: 'unknown' });
    expect(rollbackBadgeText(t, badge)).toBe('unknown — see history');
  });
});

describe('tagOptions', () => {
  it('returns nothing for no rows', () => {
    expect(tagOptions([])).toEqual([]);
  });

  it('dedupes across rows and sorts alphabetically', () => {
    const rows = [
      makeRow({ environment: { id: 'e-1', name: 'prod', tags: ['prod', 'acme'], sort_order: 1 } }),
      makeRow({ environment: { id: 'e-2', name: 'stg', tags: ['acme', 'staging'], sort_order: 2 } }),
    ];
    expect(tagOptions(rows)).toEqual([
      { value: 'acme', label: 'acme' },
      { value: 'prod', label: 'prod' },
      { value: 'staging', label: 'staging' },
    ]);
  });

  it('tolerates untagged environments', () => {
    const rows = [makeRow({ environment: { id: 'e-1', name: 'prod', tags: [], sort_order: 1 } })];
    expect(tagOptions(rows)).toEqual([]);
  });

  it('pins the selected tag even when the filtered rows no longer carry it', () => {
    expect(tagOptions([], 'prod')).toEqual([{ value: 'prod', label: 'prod' }]);
  });

  it('does not duplicate a pinned tag that is already present', () => {
    const rows = [makeRow({ environment: { id: 'e-1', name: 'prod', tags: ['prod'], sort_order: 1 } })];
    expect(tagOptions(rows, 'prod')).toHaveLength(1);
  });
});
