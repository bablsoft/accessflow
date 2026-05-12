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
  detail?: string | null;
  riskLevel?: RiskLevel | null;
}

export function ApprovalTimeline({ stages }: { stages: TimelineStage[] }) {
  const { t } = useTranslation();
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 16,
      }}
    >
      <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 14 }}>{t('timeline.title')}</div>
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column' }}>
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
                  : s.cancelled
                    ? 'var(--fg-muted)'
                    : 'var(--border-strong)';
          return (
            <div
              key={i}
              style={{
                position: 'relative',
                paddingLeft: 24,
                paddingBottom: last ? 0 : 18,
              }}
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
                style={{
                  position: 'absolute',
                  left: 0,
                  top: 4,
                  width: 14,
                  height: 14,
                  borderRadius: '50%',
                  background: 'var(--bg-elev)',
                  border: `2px solid ${dotColor}`,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
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
                style={{
                  fontSize: 12.5,
                  fontWeight: 600,
                  color: isDanger ? 'var(--risk-crit)' : undefined,
                }}
              >
                {s.label}
              </div>
              <div
                className={isDanger ? undefined : 'muted'}
                style={{
                  fontSize: 11.5,
                  marginTop: 2,
                  color: isDanger ? 'var(--risk-crit)' : undefined,
                }}
              >
                {s.who}
                {s.time && <> · {timeAgo(s.time)}</>}
              </div>
              {s.detail && (
                <div
                  style={{
                    fontSize: 11.5,
                    marginTop: 4,
                    color: 'var(--fg-muted)',
                    fontStyle: s.detail.startsWith('"') ? 'italic' : 'normal',
                  }}
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
