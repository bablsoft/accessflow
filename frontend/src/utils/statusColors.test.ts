import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  accessGrantStatusColor,
  anomalyStatusColor,
  attestationCampaignStatusColor,
  attestationItemDecisionColor,
  breakGlassStatusColor,
  deploymentOutcomeColor,
  deploymentRollbackReviewStatusColor,
  grantUsageRecommendationColor,
  statusColor,
} from './statusColors';
import type {
  AttestationCampaignStatus,
  DeploymentOutcome,
  AttestationItemDecision,
  GrantUsageRecommendation,
  BehaviorAnomalyStatus,
  BreakGlassEventStatus,
} from '@/types/api';

describe('anomalyStatusColor', () => {
  it('returns a distinct colour triple for each anomaly status', () => {
    const statuses: BehaviorAnomalyStatus[] = ['OPEN', 'ACKNOWLEDGED', 'DISMISSED'];
    for (const s of statuses) {
      const c = anomalyStatusColor(s);
      expect(c.fg).toMatch(/^var\(--/);
      expect(c.bg).toMatch(/^var\(--/);
      expect(c.border).toMatch(/^var\(--/);
    }
  });

  it('uses the critical palette for OPEN and the neutral palette for DISMISSED', () => {
    expect(anomalyStatusColor('OPEN').fg).toBe('var(--risk-crit)');
    expect(anomalyStatusColor('ACKNOWLEDGED').fg).toBe('var(--status-warn)');
    expect(anomalyStatusColor('DISMISSED').fg).toBe('var(--fg-muted)');
  });
});

describe('breakGlassStatusColor', () => {
  it('returns a colour triple for each break-glass status', () => {
    const statuses: BreakGlassEventStatus[] = ['PENDING_REVIEW', 'REVIEWED'];
    for (const s of statuses) {
      const c = breakGlassStatusColor(s);
      expect(c.fg).toMatch(/^var\(--/);
      expect(c.bg).toMatch(/^var\(--/);
      expect(c.border).toMatch(/^var\(--/);
    }
  });

  it('uses the critical palette for PENDING_REVIEW and the low palette for REVIEWED', () => {
    expect(breakGlassStatusColor('PENDING_REVIEW').fg).toBe('var(--risk-crit)');
    expect(breakGlassStatusColor('REVIEWED').fg).toBe('var(--risk-low)');
  });
});

describe('attestationCampaignStatusColor', () => {
  it('returns a colour triple for each campaign status', () => {
    const statuses: AttestationCampaignStatus[] = ['SCHEDULED', 'OPEN', 'CLOSED', 'CANCELLED'];
    for (const s of statuses) {
      const c = attestationCampaignStatusColor(s);
      expect(c.fg).toMatch(/^var\(--/);
      expect(c.bg).toMatch(/^var\(--/);
      expect(c.border).toMatch(/^var\(--/);
    }
  });

  it('uses the low palette for CLOSED and the neutral palette for CANCELLED', () => {
    expect(attestationCampaignStatusColor('OPEN').fg).toBe('var(--status-warn)');
    expect(attestationCampaignStatusColor('CLOSED').fg).toBe('var(--risk-low)');
    expect(attestationCampaignStatusColor('CANCELLED').fg).toBe('var(--fg-muted)');
  });
});

describe('attestationItemDecisionColor', () => {
  it('returns a colour triple for each item decision', () => {
    const decisions: AttestationItemDecision[] = ['PENDING', 'CERTIFIED', 'REVOKED'];
    for (const d of decisions) {
      const c = attestationItemDecisionColor(d);
      expect(c.fg).toMatch(/^var\(--/);
      expect(c.bg).toMatch(/^var\(--/);
      expect(c.border).toMatch(/^var\(--/);
    }
  });

  it('uses the low palette for CERTIFIED and the critical palette for REVOKED', () => {
    expect(attestationItemDecisionColor('CERTIFIED').fg).toBe('var(--risk-low)');
    expect(attestationItemDecisionColor('REVOKED').fg).toBe('var(--risk-crit)');
  });
});

describe('statusColor / accessGrantStatusColor smoke', () => {
  it('still resolves existing status palettes', () => {
    expect(statusColor('APPROVED').fg).toBe('var(--risk-low)');
    expect(accessGrantStatusColor('PENDING').fg).toBe('var(--status-info)');
  });
});

describe('grantUsageRecommendationColor', () => {
  const ALL: GrantUsageRecommendation[] = [
    'INSUFFICIENT_DATA',
    'NEVER_USED',
    'STALE',
    'OVER_SCOPED',
    'ACTIVE',
  ];

  it('returns a distinct colour triple for each recommendation', () => {
    const triples = ALL.map((r) => JSON.stringify(grantUsageRecommendationColor(r)));
    expect(new Set(triples).size).toBe(ALL.length);
  });

  /**
   * An undefined custom property is invalid at computed-value time, so the pill would silently
   * inherit its surrounding colour. Every token used here must exist in tokens.css.
   */
  it('only references design tokens that exist', () => {
    // Read from the project root: under jsdom import.meta.url is not a file: URL.
    const defined = readFileSync('src/styles/tokens.css', 'utf8');
    for (const recommendation of ALL) {
      const triple = grantUsageRecommendationColor(recommendation);
      for (const value of [triple.fg, triple.bg, triple.border]) {
        const token = /var\((--[a-z0-9-]+)\)/.exec(value)?.[1];
        expect(token, `${recommendation} -> ${value}`).toBeDefined();
        expect(defined, `${token} is not defined in tokens.css`).toContain(`${token}:`);
      }
    }
  });
});

describe('deploymentOutcomeColor (#696)', () => {
  const ALL: DeploymentOutcome[] = ['SUCCEEDED', 'FAILED', 'ROLLED_BACK'];

  it('maps outcomes onto the severity ramp', () => {
    expect(deploymentOutcomeColor('SUCCEEDED').fg).toBe('var(--risk-low)');
    expect(deploymentOutcomeColor('FAILED').fg).toBe('var(--risk-crit)');
    expect(deploymentOutcomeColor('ROLLED_BACK').fg).toBe('var(--status-warn)');
  });

  it('returns a distinct colour triple for each outcome', () => {
    const triples = ALL.map((o) => JSON.stringify(deploymentOutcomeColor(o)));
    expect(new Set(triples).size).toBe(ALL.length);
  });
});

describe('deploymentRollbackReviewStatusColor (#696)', () => {
  it('flags pending rollback reviews and settles reviewed ones', () => {
    expect(deploymentRollbackReviewStatusColor('PENDING_REVIEW').fg).toBe('var(--risk-crit)');
    expect(deploymentRollbackReviewStatusColor('REVIEWED').fg).toBe('var(--risk-low)');
  });
});
