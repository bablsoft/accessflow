import { useTranslation } from 'react-i18next';
import { ArrowDownOutlined, ArrowUpOutlined, MinusOutlined } from '@ant-design/icons';
import { Pill } from './Pill';

const DEFAULT_PCT_THRESHOLD = 0.2;
const DEFAULT_ABS_THRESHOLD = 0;

export interface DeltaBadgeProps {
  delta: number | null;
  previous?: number | null;
  pctThreshold?: number;
  absThreshold?: number;
  size?: 'sm' | 'md';
  unit?: string;
  /**
   * When true, a positive delta is "good" (e.g. more rows returned). When false (the
   * default) a positive delta is "bad" (e.g. slower run). Drives the red/green colour
   * choice when the magnitude is above threshold.
   */
  positiveIsGood?: boolean;
}

export function DeltaBadge({
  delta,
  previous,
  pctThreshold = DEFAULT_PCT_THRESHOLD,
  absThreshold = DEFAULT_ABS_THRESHOLD,
  size,
  unit,
  positiveIsGood = false,
}: DeltaBadgeProps) {
  const { t } = useTranslation();
  if (delta == null) {
    return null;
  }

  const magnitude = Math.abs(delta);
  const pct = previous != null && previous !== 0 ? magnitude / Math.abs(previous) : null;
  const aboveThreshold =
    magnitude > absThreshold && (pct == null || pct > pctThreshold);

  if (delta === 0) {
    return (
      <Pill
        fg="var(--fg-muted)"
        bg="var(--bg-sunken)"
        border="var(--border)"
        size={size}
      >
        <MinusOutlined aria-hidden style={{ marginRight: 4 }} />
        {t('queries.detail.diff_no_change')}
      </Pill>
    );
  }

  const direction = delta > 0 ? 'up' : 'down';
  const isGood = (delta > 0 && positiveIsGood) || (delta < 0 && !positiveIsGood);
  const palette = aboveThreshold
    ? isGood
      ? {
          fg: 'var(--risk-low)',
          bg: 'var(--risk-low-bg)',
          border: 'var(--risk-low-border)',
        }
      : {
          fg: 'var(--risk-high)',
          bg: 'var(--risk-high-bg)',
          border: 'var(--risk-high-border)',
        }
    : {
        fg: 'var(--fg-muted)',
        bg: 'var(--bg-sunken)',
        border: 'var(--border)',
      };

  const arrow = direction === 'up' ? <ArrowUpOutlined aria-hidden /> : <ArrowDownOutlined aria-hidden />;
  const sign = delta > 0 ? '+' : '';
  const label = `${sign}${delta}${unit ? ` ${unit}` : ''}`;
  return (
    <Pill fg={palette.fg} bg={palette.bg} border={palette.border} size={size}>
      <span style={{ marginRight: 4, display: 'inline-flex' }}>{arrow}</span>
      <span>{label}</span>
    </Pill>
  );
}
