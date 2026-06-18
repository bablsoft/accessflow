import { Button } from 'antd';
import { CheckOutlined, ThunderboltOutlined, WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AiAnalysis } from '@/types/api';
import { IssueCard } from './IssueCard';
import { OptimizationCard } from './OptimizationCard';
import { fmtNum } from '@/utils/dateFormat';

interface AiHintPanelProps {
  analyzing: boolean;
  analysis: AiAnalysis | null;
  stale?: boolean;
  aiEnabled: boolean;
  onApplySuggestion?: (sql: string) => void;
  onReanalyze?: () => void;
}

const riskBg = (level: AiAnalysis['risk_level']) => {
  switch (level) {
    case 'LOW': return 'var(--risk-low)';
    case 'MEDIUM': return 'var(--risk-med)';
    case 'HIGH': return 'var(--risk-high)';
    case 'CRITICAL': return 'var(--risk-crit)';
  }
};

export function AiHintPanel({
  analyzing,
  analysis,
  stale = false,
  aiEnabled,
  onApplySuggestion,
  onReanalyze,
}: AiHintPanelProps) {
  const { t } = useTranslation();
  // Stale only matters while an analysis is actually displayed (not during a re-run).
  const showStale = stale && !!analysis && !analyzing;
  return (
    <div
      style={{
        background: 'var(--bg-sunken)',
        overflow: 'auto',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
      }}
    >
      <div
        style={{
          padding: '12px 16px',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <ThunderboltOutlined style={{ color: 'var(--accent)' }} />
        <span style={{ fontWeight: 600, fontSize: 13 }}>{t('ai_panel.title')}</span>
        {showStale && (
          <span
            className="mono"
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              marginLeft: 'auto',
              fontSize: 10,
              padding: '2px 6px',
              borderRadius: 999,
              color: 'var(--risk-med)',
              background: 'var(--risk-med-bg)',
              border: '1px solid var(--risk-med)',
            }}
          >
            <WarningOutlined style={{ fontSize: 10 }} />
            {t('ai_panel.stale_badge')}
          </span>
        )}
        {!aiEnabled && (
          <span
            className="mono muted"
            style={{
              marginLeft: 'auto',
              fontSize: 10,
              padding: '2px 6px',
              borderRadius: 999,
              background: 'var(--bg-elev)',
              border: '1px solid var(--border)',
            }}
          >
            {t('ai_panel.disabled_badge')}
          </span>
        )}
      </div>
      <div style={{ flex: 1, padding: 16, overflow: 'auto' }}>
        {analyzing ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div className="skeleton" style={{ height: 14, width: '60%' }} />
            <div className="skeleton" style={{ height: 36, width: '100%' }} />
            <div className="skeleton" style={{ height: 14, width: '80%' }} />
            <div className="skeleton" style={{ height: 60, width: '100%' }} />
            <div className="skeleton" style={{ height: 60, width: '100%' }} />
          </div>
        ) : !analysis ? (
          <div className="muted" style={{ fontSize: 12, padding: '40px 0', textAlign: 'center' }}>
            {t('ai_panel.empty_prompt_analyze')}
          </div>
        ) : (
          <>
            {showStale && (
              <div
                role="status"
                style={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: 10,
                  padding: 12,
                  marginBottom: 16,
                  background: 'var(--risk-med-bg)',
                  border: '1px solid var(--risk-med)',
                  borderRadius: 'var(--radius-md)',
                  fontSize: 12,
                  lineHeight: 1.5,
                }}
              >
                <WarningOutlined style={{ color: 'var(--risk-med)', marginTop: 2, flexShrink: 0 }} />
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
                  <span>{t('ai_panel.stale_banner')}</span>
                  {onReanalyze && (
                    <div>
                      <Button size="small" icon={<ThunderboltOutlined />} onClick={onReanalyze}>
                        {t('ai_panel.reanalyze_button')}
                      </Button>
                    </div>
                  )}
                </div>
              </div>
            )}
            <div style={{ marginBottom: 16 }}>
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  marginBottom: 8,
                }}
              >
                <span
                  className="muted"
                  style={{
                    fontSize: 11,
                    textTransform: 'uppercase',
                    letterSpacing: '0.04em',
                    fontWeight: 500,
                  }}
                >
                  {t('ai_panel.risk_score_label')}
                </span>
                <span className="mono" style={{ fontSize: 13, fontWeight: 600 }}>
                  {analysis.risk_score}/100
                </span>
              </div>
              <div
                style={{
                  height: 6,
                  background: 'var(--bg-elev)',
                  borderRadius: 3,
                  overflow: 'hidden',
                }}
              >
                <div
                  style={{
                    height: '100%',
                    width: `${analysis.risk_score}%`,
                    background: riskBg(analysis.risk_level),
                    transition: 'width 0.4s, background 0.3s',
                  }}
                />
              </div>
            </div>

            <div
              style={{
                background: 'var(--bg-elev)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)',
                padding: 12,
                marginBottom: 16,
                fontSize: 12.5,
                lineHeight: 1.55,
              }}
            >
              {analysis.summary}
            </div>

            {analysis.issues.length > 0 ? (
              <>
                <div
                  className="muted"
                  style={{
                    fontSize: 11,
                    textTransform: 'uppercase',
                    letterSpacing: '0.04em',
                    fontWeight: 500,
                    marginBottom: 8,
                  }}
                >
                  {t('ai_panel.issues_count', { count: analysis.issues.length })}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {analysis.issues.map((iss, i) => <IssueCard key={i} issue={iss} />)}
                </div>
              </>
            ) : (
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  padding: 12,
                  background: 'var(--risk-low-bg)',
                  color: 'var(--risk-low)',
                  borderRadius: 6,
                  fontSize: 12,
                }}
              >
                <CheckOutlined /> {t('ai_panel.no_issues')}
              </div>
            )}

            {analysis.optimizations && analysis.optimizations.length > 0 && (
              <div style={{ marginTop: 16 }}>
                <div
                  className="muted"
                  style={{
                    fontSize: 11,
                    textTransform: 'uppercase',
                    letterSpacing: '0.04em',
                    fontWeight: 500,
                    marginBottom: 8,
                  }}
                >
                  {t('ai_panel.optimizations_count', { count: analysis.optimizations.length })}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {analysis.optimizations.map((opt, i) => (
                    <OptimizationCard key={i} optimization={opt} onApply={onApplySuggestion} />
                  ))}
                </div>
              </div>
            )}

            <div style={{ marginTop: 16, paddingTop: 16, borderTop: '1px solid var(--border)' }}>
              <div
                className="muted"
                style={{
                  fontSize: 11,
                  textTransform: 'uppercase',
                  letterSpacing: '0.04em',
                  fontWeight: 500,
                  marginBottom: 8,
                }}
              >
                {t('ai_panel.metadata_label')}
              </div>
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 6,
                  fontSize: 11.5,
                  fontFamily: 'var(--font-mono)',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span className="muted">{t('ai_panel.metadata_provider')}</span>
                  <span>anthropic</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span className="muted">{t('ai_panel.metadata_model')}</span>
                  <span>claude-sonnet-4</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span className="muted">{t('ai_panel.metadata_est_rows')}</span>
                  <span>{fmtNum(analysis.affects_rows ?? 1)}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span className="muted">{t('ai_panel.metadata_tokens')}</span>
                  <span>
                    {analysis.prompt_tokens}/{analysis.completion_tokens}
                  </span>
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
