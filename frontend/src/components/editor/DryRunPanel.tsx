import { Button } from 'antd';
import { ExperimentOutlined, InfoCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { QueryDryRunResult } from '@/types/api';
import { fmtNum } from '@/utils/dateFormat';
import { queryTypeLabel } from '@/utils/enumLabels';
import { formatBytes } from '@/utils/queryPlan';
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
      className="u-flex u-col" style={{ background: 'var(--bg-sunken)', overflow: 'auto', minHeight: 0 }}
    >
      <div
        className="u-flex u-items-center u-gap-2" style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)' }}
      >
        <ExperimentOutlined className="u-fg-accent" />
        <span className="u-fw-600 u-text-sm">{t('dry_run_panel.title')}</span>
        {showStale && (
          <span
            className="mono u-inline-flex u-items-center u-gap-1 u-ml-auto u-text-2xs"
            style={{ padding: '2px 6px', borderRadius: 999, color: 'var(--risk-med)', background: 'var(--risk-med-bg)', border: '1px solid var(--risk-med)' }}
          >
            <WarningOutlined style={{ fontSize: 10 }} />
            {t('dry_run_panel.stale_badge')}
          </span>
        )}
      </div>
      <div className="u-flex-1 u-p-4" style={{ overflow: 'auto' }}>
        {running ? (
          <div className="u-flex u-col" style={{ gap: 10 }}>
            <div className="skeleton" style={{ height: 14, width: '50%' }} />
            <div className="skeleton u-w-full" style={{ height: 40 }} />
            <div className="skeleton" style={{ height: 40, width: '90%' }} />
            <div className="skeleton" style={{ height: 40, width: '95%' }} />
          </div>
        ) : !result ? (
          <div className="muted u-text-xs u-text-center" style={{ padding: '40px 0' }}>
            {t('dry_run_panel.empty_prompt')}
          </div>
        ) : !result.supported ? (
          <div
            role="status"
            className="u-flex u-items-start u-p-3 u-text-xs u-leading-normal" style={{ gap: 10, background: 'var(--bg-elev)', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)' }}
          >
            <InfoCircleOutlined className="u-fg-faint" style={{ marginTop: 2, flexShrink: 0 }} />
            <span>{result.unsupported_reason ?? t('dry_run_panel.unsupported')}</span>
          </div>
        ) : (
          <>
            <div
              className="u-flex u-col u-p-3 u-mb-4 u-text-xs u-mono" style={{ gap: 6, background: 'var(--bg-elev)', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)' }}
            >
              <div
                className="muted u-text-2xs u-fw-500"
                style={{ textTransform: 'uppercase', letterSpacing: '0.04em', fontFamily: 'inherit', marginBottom: 2 }}
              >
                {t('dry_run_panel.estimated_impact_label')}
              </div>
              {result.query_type && (
                <div className="u-flex u-justify-between">
                  <span className="muted">{t('dry_run_panel.query_type_label')}</span>
                  <span>{queryTypeLabel(t, result.query_type)}</span>
                </div>
              )}
              <div className="u-flex u-justify-between">
                <span className="muted">{t('dry_run_panel.est_rows_label')}</span>
                <span>
                  {result.estimated_rows === null || result.estimated_rows === undefined
                    ? '—'
                    : fmtNum(result.estimated_rows)}
                </span>
              </div>
              {result.estimated_bytes_scanned !== null &&
                result.estimated_bytes_scanned !== undefined && (
                  <div className="u-flex u-justify-between">
                    <span className="muted">{t('dry_run_panel.est_bytes_label')}</span>
                    <span>{formatBytes(result.estimated_bytes_scanned)}</span>
                  </div>
                )}
            </div>

            {result.plan ? (
              <PlanTree plan={result.plan} />
            ) : result.raw_plan ? (
              <pre
                className="mono u-m-0 u-p-2 u-text-2xs"
                style={{ background: 'var(--bg-elev)', border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
              >
                <code>{result.raw_plan}</code>
              </pre>
            ) : (
              <div className="muted u-text-xs">
                {t('dry_run_panel.no_plan')}
              </div>
            )}
          </>
        )}
      </div>
      {onRun && !running && (
        <div className="u-p-3" style={{ borderTop: '1px solid var(--border)' }}>
          <Button size="small" icon={<ExperimentOutlined />} onClick={onRun} block>
            {t('dry_run_panel.rerun_button')}
          </Button>
        </div>
      )}
    </div>
  );
}
