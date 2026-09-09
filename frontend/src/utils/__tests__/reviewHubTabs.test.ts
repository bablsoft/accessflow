import { describe, expect, it } from 'vitest';
import type { AuthUser } from '@/api/auth';
import {
  REVIEW_HUB_PERMISSIONS,
  REVIEW_HUB_TAB_KEYS,
  isReviewHubTabKey,
  resolveReviewHubTab,
  reviewHubPath,
  permittedReviewHubTabs,
  visibleReviewHubTabs,
} from '../reviewHubTabs';
import type { GovernanceDomains } from '@/hooks/useGovernanceDomains';

const ALL_DOMAINS: GovernanceDomains = { apis: true, deployments: true };
const DB_ONLY: GovernanceDomains = { apis: false, deployments: false };

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
    expect(visibleReviewHubTabs(user(['QUERY_REVIEW']), ALL_DOMAINS)).toEqual(['queries']);
    expect(visibleReviewHubTabs(user(['API_REQUEST_REVIEW']), ALL_DOMAINS)).toEqual(['api']);
    // DEPLOYMENT_REVIEW opens both deployment tabs.
    expect(visibleReviewHubTabs(user(['DEPLOYMENT_REVIEW']), ALL_DOMAINS))
      .toEqual(['deployments', 'rollbacks']);
    expect(
      visibleReviewHubTabs(
        user(['DEPLOYMENT_REVIEW', 'QUERY_REVIEW', 'API_REQUEST_REVIEW']),
        ALL_DOMAINS,
      ),
    ).toEqual(['queries', 'api', 'deployments', 'rollbacks']);
    expect(visibleReviewHubTabs(user(['QUERY_SUBMIT_SELECT']), ALL_DOMAINS)).toEqual([]);
    expect(visibleReviewHubTabs(null, ALL_DOMAINS)).toEqual([]);
  });

  describe('governance domains (#926)', () => {
    const everything = user(['QUERY_REVIEW', 'API_REQUEST_REVIEW', 'DEPLOYMENT_REVIEW']);

    it('drops the tabs whose domain the organization switched off', () => {
      expect(visibleReviewHubTabs(everything, { apis: false, deployments: true }))
        .toEqual(['queries', 'deployments', 'rollbacks']);
      expect(visibleReviewHubTabs(everything, { apis: true, deployments: false }))
        .toEqual(['queries', 'api']);
      expect(visibleReviewHubTabs(everything, DB_ONLY)).toEqual(['queries']);
    });

    it('never adds a tab the viewer lacks the permission for', () => {
      expect(visibleReviewHubTabs(user(['QUERY_REVIEW']), ALL_DOMAINS)).toEqual(['queries']);
      expect(permittedReviewHubTabs(user(['QUERY_REVIEW']))).toEqual(['queries']);
    });

    it('still honours an explicit deep link into a switched-off domain', () => {
      expect(resolveReviewHubTab('deployments', everything, DB_ONLY)).toBe('deployments');
      expect(resolveReviewHubTab('api', everything, DB_ONLY)).toBe('api');
    });

    it('lands a reviewer whose only domain is off on a tab they can still open', () => {
      const apiReviewer = user(['API_REQUEST_REVIEW']);
      expect(visibleReviewHubTabs(apiReviewer, DB_ONLY)).toEqual([]);
      expect(resolveReviewHubTab(null, apiReviewer, DB_ONLY)).toBe('api');
    });
  });

  describe('resolveReviewHubTab', () => {
    const reviewer = user(['QUERY_REVIEW', 'DEPLOYMENT_REVIEW']);

    it('honours a requested tab the viewer may see', () => {
      expect(resolveReviewHubTab('rollbacks', reviewer, ALL_DOMAINS)).toBe('rollbacks');
    });

    it('falls back to the first visible tab when nothing is requested', () => {
      expect(resolveReviewHubTab(null, reviewer, ALL_DOMAINS)).toBe('queries');
      expect(resolveReviewHubTab(undefined, user(['DEPLOYMENT_REVIEW']), ALL_DOMAINS))
        .toBe('deployments');
    });

    it('falls back to the first visible tab for an unknown or unpermitted request', () => {
      expect(resolveReviewHubTab('bogus', reviewer, ALL_DOMAINS)).toBe('queries');
      expect(resolveReviewHubTab('api', reviewer, ALL_DOMAINS)).toBe('queries');
      expect(resolveReviewHubTab('queries', user(['API_REQUEST_REVIEW']), ALL_DOMAINS)).toBe('api');
    });

    it('returns null when the viewer may review nothing', () => {
      expect(resolveReviewHubTab('queries', user(['QUERY_SUBMIT_SELECT']), ALL_DOMAINS)).toBeNull();
      expect(resolveReviewHubTab(null, null, ALL_DOMAINS)).toBeNull();
    });
  });

  it('builds the hub URL from the tab key', () => {
    expect(reviewHubPath('queries')).toBe('/reviews?tab=queries');
    expect(reviewHubPath('api')).toBe('/reviews?tab=api');
    expect(reviewHubPath('deployments')).toBe('/reviews?tab=deployments');
    expect(reviewHubPath('rollbacks')).toBe('/reviews?tab=rollbacks');
  });
});
