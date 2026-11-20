import { useTranslation } from 'react-i18next';
import { timeAgo } from '@/utils/dateFormat';
import { riskColor } from '@/utils/riskColors';
import type { RiskLevel } from '@/types/api';

export interface TimelineStage {
  label: string;
  who: string;
  time?: string | null;
  done?: boolean;
  active?: boolean;
  rejected?: boolean;
  failed?: boolean;
  cancelled?: boolean;
  skipped?: boolean;
  detail?: string | null;
  riskLevel?: RiskLevel | null;
}

export function ApprovalTimeline({ stages }: { stages: TimelineStage[] }) {
  const { t } = useTranslation();
  return (
    <div
      className="af-box u-p-4"
    >
      <div className="u-fw-600 u-text-sm u-mb-4">{t('timeline.title')}</div>
      <div className="u-flex u-col u-relative">
        {stages.map((s, i) => {
          const last = i === stages.length - 1;
          const isDanger = !!(s.rejected || s.failed);
          const riskFg = s.riskLevel ? riskColor(s.riskLevel).fg : null;
          const dotColor = isDanger
            ? 'var(--risk-crit)'
            : riskFg && (s.done || s.active)
              ? riskFg
              : s.done
                ? 'var(--risk-low)'
                : s.active
                  ? 'var(--accent)'
                  : s.cancelled || s.skipped
                    ? 'var(--fg-muted)'
                    : 'var(--border-strong)';
          return (
            <div
              key={i}
              className="u-relative" style={{ paddingLeft: 24, paddingBottom: last ? 0 : 18 }}
            >
              {!last && (
                <div
                  style={{
                    position: 'absolute',
                    left: 7,
                    top: 16,
                    bottom: 0,
                    width: 1,
                    background: 'var(--border)',
                  }}
                />
              )}
              <div
                className="af-timeline-dot u-flex u-items-center u-justify-center u-rounded-full u-bg-elev" style={{ position: 'absolute', left: 0, top: 4, width: 14, height: 14, border: `2px solid ${dotColor}` }}
              >
                {(s.done || s.active) && (
                  <span
                    className={s.active ? 'pulse' : undefined}
                    style={{
                      width: 6,
                      height: 6,
                      borderRadius: '50%',
                      background: dotColor,
                    }}
                  />
                )}
              </div>
              <div
                className="u-text-sm u-fw-600" style={{ color: isDanger ? 'var(--risk-crit)' : undefined }}
              >
                {s.label}
              </div>
              <div
                className={isDanger ? undefined : 'muted'}
                style={{
                  fontSize: 12,
                  marginTop: 2,
                  color: isDanger ? 'var(--risk-crit)' : undefined,
                }}
              >
                {s.who}
                {s.time && <> · {timeAgo(s.time)}</>}
              </div>
              {s.detail && (
                <div
                  className="u-text-xs u-mt-1 u-fg-muted" style={{ fontStyle: s.detail.startsWith('"') ? 'italic' : 'normal' }}
                >
                  {s.detail}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
