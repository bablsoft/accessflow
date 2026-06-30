import { describe, expect, it } from 'vitest';
import { aggregateRisk } from './requestGroupRisk';

describe('aggregateRisk', () => {
  it('returns null when no member has been analysed', () => {
    expect(aggregateRisk([])).toBeNull();
    expect(
      aggregateRisk([
        { ai_risk_level: null, ai_risk_score: null },
        { ai_risk_level: null, ai_risk_score: null },
      ]),
    ).toBeNull();
  });

  it('picks the worst (highest) risk level across members', () => {
    expect(
      aggregateRisk([
        { ai_risk_level: 'LOW', ai_risk_score: 10 },
        { ai_risk_level: 'HIGH', ai_risk_score: 70 },
        { ai_risk_level: 'MEDIUM', ai_risk_score: 40 },
      ]),
    ).toEqual({ level: 'HIGH', score: 70 });
  });

  it('treats CRITICAL as the worst', () => {
    expect(
      aggregateRisk([
        { ai_risk_level: 'HIGH', ai_risk_score: 80 },
        { ai_risk_level: 'CRITICAL', ai_risk_score: 95 },
      ]),
    ).toEqual({ level: 'CRITICAL', score: 95 });
  });

  it('takes the max numeric score even when it is on a lower-level member', () => {
    expect(
      aggregateRisk([
        { ai_risk_level: 'HIGH', ai_risk_score: 60 },
        { ai_risk_level: 'LOW', ai_risk_score: 90 },
      ]),
    ).toEqual({ level: 'HIGH', score: 90 });
  });

  it('defaults score to 0 when a level is present but no score', () => {
    expect(aggregateRisk([{ ai_risk_level: 'MEDIUM', ai_risk_score: null }])).toEqual({
      level: 'MEDIUM',
      score: 0,
    });
  });
});
