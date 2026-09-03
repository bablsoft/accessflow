import { describe, expect, it } from 'vitest';
import type { AuthUser } from '@/api/auth';
import {
  REVIEW_HUB_PERMISSIONS,
  REVIEW_HUB_TAB_KEYS,
  isReviewHubTabKey,
  resolveReviewHubTab,
  reviewHubPath,
  visibleReviewHubTabs,
} from '../reviewHubTabs';

function user(permissions: string[]): AuthUser {
  return {
    id: 'u-1',
    email: 'u@example.com',
    display_name: 'U',
    role: 'REVIEWER',
    role_id: null,
    permissions,
    auth_provider: 'LOCAL',
    totp_enabled: false,
    platform_admin: false,
    preferred_language: null,
  };
}

describe('reviewHubTabs (#772)', () => {
  it('gates the hub on the union of the per-tab permissions', () => {
    expect(REVIEW_HUB_PERMISSIONS).toEqual(['QUERY_REVIEW', 'API_REQUEST_REVIEW', 'DEPLOYMENT_REVIEW']);
  });

  it('recognises only the four tab keys', () => {
    for (const key of REVIEW_HUB_TAB_KEYS) expect(isReviewHubTabKey(key)).toBe(true);
    expect(isReviewHubTabKey('bogus')).toBe(false);
    expect(isReviewHubTabKey(null)).toBe(false);
    expect(isReviewHubTabKey(undefined)).toBe(false);
  });

  it('lists the visible tabs in display order, one per held permission', () => {
    expect(visibleReviewHubTabs(user(['QUERY_REVIEW']))).toEqual(['queries']);
    expect(visibleReviewHubTabs(user(['API_REQUEST_REVIEW']))).toEqual(['api']);
    // DEPLOYMENT_REVIEW opens both deployment tabs.
    expect(visibleReviewHubTabs(user(['DEPLOYMENT_REVIEW']))).toEqual(['deployments', 'rollbacks']);
    expect(
      visibleReviewHubTabs(user(['DEPLOYMENT_REVIEW', 'QUERY_REVIEW', 'API_REQUEST_REVIEW'])),
    ).toEqual(['queries', 'api', 'deployments', 'rollbacks']);
    expect(visibleReviewHubTabs(user(['QUERY_SUBMIT_SELECT']))).toEqual([]);
    expect(visibleReviewHubTabs(null)).toEqual([]);
  });

  describe('resolveReviewHubTab', () => {
    const reviewer = user(['QUERY_REVIEW', 'DEPLOYMENT_REVIEW']);

    it('honours a requested tab the viewer may see', () => {
      expect(resolveReviewHubTab('rollbacks', reviewer)).toBe('rollbacks');
    });

    it('falls back to the first visible tab when nothing is requested', () => {
      expect(resolveReviewHubTab(null, reviewer)).toBe('queries');
      expect(resolveReviewHubTab(undefined, user(['DEPLOYMENT_REVIEW']))).toBe('deployments');
    });

    it('falls back to the first visible tab for an unknown or unpermitted request', () => {
      expect(resolveReviewHubTab('bogus', reviewer)).toBe('queries');
      expect(resolveReviewHubTab('api', reviewer)).toBe('queries');
      expect(resolveReviewHubTab('queries', user(['API_REQUEST_REVIEW']))).toBe('api');
    });

    it('returns null when the viewer may review nothing', () => {
      expect(resolveReviewHubTab('queries', user(['QUERY_SUBMIT_SELECT']))).toBeNull();
      expect(resolveReviewHubTab(null, null)).toBeNull();
    });
  });

  it('builds the hub URL from the tab key', () => {
    expect(reviewHubPath('queries')).toBe('/reviews?tab=queries');
    expect(reviewHubPath('api')).toBe('/reviews?tab=api');
    expect(reviewHubPath('deployments')).toBe('/reviews?tab=deployments');
    expect(reviewHubPath('rollbacks')).toBe('/reviews?tab=rollbacks');
  });
});
