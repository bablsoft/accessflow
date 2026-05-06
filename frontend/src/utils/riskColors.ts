import type { RiskLevel } from '@/types/api';

export interface ColorTriple {
  fg: string;
  bg: string;
  border: string;
}

export const riskColor = (level: RiskLevel): ColorTriple => {
  switch (level) {
    case 'LOW':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
    case 'MEDIUM':
      return { fg: 'var(--risk-med)', bg: 'var(--risk-med-bg)', border: 'var(--risk-med-border)' };
    case 'HIGH':
      return { fg: 'var(--risk-high)', bg: 'var(--risk-high-bg)', border: 'var(--risk-high-border)' };
    case 'CRITICAL':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
  }
};
