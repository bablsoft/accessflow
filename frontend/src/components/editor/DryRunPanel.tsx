import { Button } from 'antd';
import { ExperimentOutlined, InfoCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { QueryDryRunResult } from '@/types/api';
import { fmtNum } from '@/utils/dateFormat';
import { queryTypeLabel } from '@/utils/enumLabels';
import { PlanTree } from './PlanTree';

interface DryRunPanelProps {
  running: boolean;
  result: QueryDryRunResult | null;
  stale?: boolean;
  onRun?: () => void;
}

/** Right-rail panel showing the dry-run execution plan + estimated impact (AF-445). */
export function DryRunPanel({ running, result, stale = false, onRun }: DryRunPanelProps) {
  const { t } = useTranslation();
  const showStale = stale && !!result && !running;
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
        <ExperimentOutlined style={{ color: 'var(--accent)' }} />
        <span style={{ fontWeight: 600, fontSize: 13 }}>{t('dry_run_panel.title')}</span>
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
            {t('dry_run_panel.stale_badge')}
          </span>
        )}
      </div>
      <div style={{ flex: 1, padding: 16, overflow: 'auto' }}>
        {running ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div className="skeleton" style={{ height: 14, width: '50%' }} />
            <div className="skeleton" style={{ height: 40, width: '100%' }} />
            <div className="skeleton" style={{ height: 40, width: '90%' }} />
            <div className="skeleton" style={{ height: 40, width: '95%' }} />
          </div>
        ) : !result ? (
          <div className="muted" style={{ fontSize: 12, padding: '40px 0', textAlign: 'center' }}>
            {t('dry_run_panel.empty_prompt')}
          </div>
        ) : !result.supported ? (
          <div
            role="status"
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 10,
              padding: 12,
              background: 'var(--bg-elev)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius-md)',
              fontSize: 12,
              lineHeight: 1.5,
            }}
          >
            <InfoCircleOutlined style={{ color: 'var(--fg-faint)', marginTop: 2, flexShrink: 0 }} />
            <span>{result.unsupported_reason ?? t('dry_run_panel.unsupported')}</span>
          </div>
        ) : (
          <>
            <div
              style={{
                display: 'flex',
                flexDirection: 'column',
                gap: 6,
                padding: 12,
                marginBottom: 16,
                background: 'var(--bg-elev)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)',
                fontSize: 11.5,
                fontFamily: 'var(--font-mono)',
              }}
            >
              <div
                className="muted"
                style={{
                  fontSize: 11,
                  textTransform: 'uppercase',
                  letterSpacing: '0.04em',
                  fontWeight: 500,
                  fontFamily: 'inherit',
                  marginBottom: 2,
                }}
              >
                {t('dry_run_panel.estimated_impact_label')}
              </div>
              {result.query_type && (
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span className="muted">{t('dry_run_panel.query_type_label')}</span>
                  <span>{queryTypeLabel(t, result.query_type)}</span>
                </div>
              )}
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span className="muted">{t('dry_run_panel.est_rows_label')}</span>
                <span>
                  {result.estimated_rows === null || result.estimated_rows === undefined
                    ? '—'
                    : fmtNum(result.estimated_rows)}
                </span>
              </div>
            </div>

            {result.plan ? (
              <PlanTree plan={result.plan} />
            ) : result.raw_plan ? (
              <pre
                className="mono"
                style={{
                  margin: 0,
                  padding: 8,
                  background: 'var(--bg-elev)',
                  border: '1px solid var(--border)',
                  borderRadius: 'var(--radius-sm)',
                  fontSize: 11,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}
              >
                <code>{result.raw_plan}</code>
              </pre>
            ) : (
              <div className="muted" style={{ fontSize: 12 }}>
                {t('dry_run_panel.no_plan')}
              </div>
            )}
          </>
        )}
      </div>
      {onRun && !running && (
        <div style={{ padding: 12, borderTop: '1px solid var(--border)' }}>
          <Button size="small" icon={<ExperimentOutlined />} onClick={onRun} block>
            {t('dry_run_panel.rerun_button')}
          </Button>
        </div>
      )}
    </div>
  );
}
