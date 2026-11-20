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
      className="u-flex u-col u-bg-sunken u-overflow-auto u-min-h-0"
    >
      <div
        className="u-flex u-items-center u-gap-2 u-py-3 u-px-4 u-border-b"
      >
        <ThunderboltOutlined className="u-fg-accent" />
        <span className="u-fw-600 u-text-sm">{t('ai_panel.title')}</span>
        {showStale && (
          <span
            className="mono u-inline-flex u-items-center u-gap-1 u-ml-auto u-text-2xs u-rounded-pill u-fg-risk-med"
            style={{ padding: '2px 6px', background: 'var(--risk-med-bg)', border: '1px solid var(--risk-med)' }}
          >
            <WarningOutlined className="u-text-2xs" />
            {t('ai_panel.stale_badge')}
          </span>
        )}
        {!aiEnabled && (
          <span
            className="mono muted u-ml-auto u-text-2xs u-rounded-pill u-bg-elev u-border"
            style={{ padding: '2px 6px' }}
          >
            {t('ai_panel.disabled_badge')}
          </span>
        )}
      </div>
      <div className="u-flex-1 u-p-4 u-overflow-auto">
        {analyzing ? (
          <div className="u-flex u-col u-gap-2">
            <div className="skeleton" style={{ height: 14, width: '60%' }} />
            <div className="skeleton u-w-full" style={{ height: 36 }} />
            <div className="skeleton" style={{ height: 14, width: '80%' }} />
            <div className="skeleton u-w-full" style={{ height: 60 }} />
            <div className="skeleton u-w-full" style={{ height: 60 }} />
          </div>
        ) : !analysis ? (
          <div className="muted u-text-xs u-text-center u-py-10 u-px-0">
            {t('ai_panel.empty_prompt_analyze')}
          </div>
        ) : (
          <>
            {showStale && (
              <div
                role="status"
                className="u-flex u-items-start u-p-3 u-mb-4 u-text-xs u-leading-normal u-rounded-md u-gap-2" style={{ background: 'var(--risk-med-bg)', border: '1px solid var(--risk-med)' }}
              >
                <WarningOutlined className="u-fg-risk-med u-shrink-0 u-mt-1" />
                <div className="u-flex u-col u-gap-2 u-min-w-0">
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
            <div className="u-mb-4">
              <div
                className="u-flex u-justify-between u-items-center u-mb-2"
              >
                <span
                  className="muted u-text-2xs u-fw-500 u-uppercase u-tracking-wide"
                >
                  {t('ai_panel.risk_score_label')}
                </span>
                <span className="mono u-text-sm u-fw-600">
                  {analysis.risk_score}/100
                </span>
              </div>
              <div
                className="u-bg-elev u-overflow-hidden" style={{ height: 6, borderRadius: 3 }}
              >
                <div
                  className="u-h-full" style={{ width: `${analysis.risk_score}%`, background: riskBg(analysis.risk_level), transition: 'width 0.4s, background 0.3s' }}
                />
              </div>
            </div>

            <div
              className="af-box u-p-3 u-mb-4 u-text-sm u-leading-normal"
            >
              {analysis.summary}
            </div>

            {analysis.issues.length > 0 ? (
              <>
                <div
                  className="muted u-text-2xs u-fw-500 u-mb-2 u-uppercase u-tracking-wide"
                >
                  {t('ai_panel.issues_count', { count: analysis.issues.length })}
                </div>
                <div className="u-flex u-col u-gap-2">
                  {analysis.issues.map((iss, i) => <IssueCard key={i} issue={iss} />)}
                </div>
              </>
            ) : (
              <div
                className="u-flex u-items-center u-gap-2 u-p-3 u-fg-success u-text-xs u-rounded" style={{ background: 'var(--risk-low-bg)' }}
              >
                <CheckOutlined /> {t('ai_panel.no_issues')}
              </div>
            )}

            {analysis.optimizations && analysis.optimizations.length > 0 && (
              <div className="u-mt-4">
                <div
                  className="muted u-text-2xs u-fw-500 u-mb-2 u-uppercase u-tracking-wide"
                >
                  {t('ai_panel.optimizations_count', { count: analysis.optimizations.length })}
                </div>
                <div className="u-flex u-col u-gap-2">
                  {analysis.optimizations.map((opt, i) => (
                    <OptimizationCard key={i} optimization={opt} onApply={onApplySuggestion} />
                  ))}
                </div>
              </div>
            )}

            <div className="u-mt-4 u-pt-4 u-border-t">
              <div
                className="muted u-text-2xs u-fw-500 u-mb-2 u-uppercase u-tracking-wide"
              >
                {t('ai_panel.metadata_label')}
              </div>
              <div
                className="u-flex u-col u-text-xs u-mono u-gap-2"
              >
                <div className="u-flex u-justify-between">
                  <span className="muted">{t('ai_panel.metadata_provider')}</span>
                  <span>anthropic</span>
                </div>
                <div className="u-flex u-justify-between">
                  <span className="muted">{t('ai_panel.metadata_model')}</span>
                  <span>claude-sonnet-4</span>
                </div>
                <div className="u-flex u-justify-between">
                  <span className="muted">{t('ai_panel.metadata_est_rows')}</span>
                  <span>{fmtNum(analysis.affects_rows ?? 1)}</span>
                </div>
                <div className="u-flex u-justify-between">
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
