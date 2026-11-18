import type { ReactNode } from 'react';
import { Pill } from '@/components/common/Pill';
import type { ColorTriple } from '@/utils/riskColors';

export type StatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'accent';

const triple = (name: string): ColorTriple => ({
  fg: `var(--${name})`,
  bg: `var(--${name}-bg)`,
  border: `var(--${name}-border)`,
});

const TONES: Record<StatusTone, ColorTriple> = {
  neutral: triple('status-neutral'),
  info: triple('status-info'),
  success: triple('risk-low'),
  warning: triple('status-warn'),
  danger: triple('risk-crit'),
  accent: { fg: 'var(--accent-strong)', bg: 'var(--accent-bg)', border: 'var(--accent-border)' },
};

interface StatusTagProps {
  tone?: StatusTone;
  size?: 'sm' | 'md';
  withDot?: boolean;
  children: ReactNode;
}

/** A status label in one of six tones — use instead of an Ant Design `Tag color=`. */
export function StatusTag({ tone = 'neutral', size, withDot, children }: StatusTagProps) {
  return (
    <Pill {...TONES[tone]} size={size} withDot={withDot}>
      {children}
    </Pill>
  );
}
