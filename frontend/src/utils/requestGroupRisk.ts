import type { RiskLevel } from '@/types/api';

const ORDER: readonly RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;

export interface Riskish {
  ai_risk_level: RiskLevel | null;
  ai_risk_score: number | null;
}

export interface AggregateRisk {
  level: RiskLevel;
  score: number;
}

/**
 * Aggregate risk of a request group is the worst (highest) per-member risk — a chain is only as
 * safe as its riskiest step. The aggregate score is the max numeric score across members that
 * carry one. Returns null when no member has been analysed yet.
 */
export function aggregateRisk(items: readonly Riskish[]): AggregateRisk | null {
  let bestRank = -1;
  let level: RiskLevel | null = null;
  let score = -1;
  for (const item of items) {
    if (item.ai_risk_level != null) {
      const rank = ORDER.indexOf(item.ai_risk_level);
      if (rank > bestRank) {
        bestRank = rank;
        level = item.ai_risk_level;
      }
    }
    if (item.ai_risk_score != null && item.ai_risk_score > score) {
      score = item.ai_risk_score;
    }
  }
  if (level == null) return null;
  return { level, score: score < 0 ? 0 : score };
}
