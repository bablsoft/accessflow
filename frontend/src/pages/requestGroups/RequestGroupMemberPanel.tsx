import { useState } from 'react';
import { Card, Tag } from 'antd';
import { DownOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { DetailCard } from '@/components/common/DetailCard';
import { SqlBlock } from '@/components/common/SqlBlock';
import { RiskPill } from '@/components/common/RiskPill';
import { RequestGroupItemStatusPill } from '@/components/common/RequestGroupItemStatusPill';
import { IssueCard } from '@/components/editor/IssueCard';
import { OptimizationCard } from '@/components/editor/OptimizationCard';
import { targetKindLabel } from '@/utils/enumLabels';
import type { RequestGroupItem } from '@/types/api';

export function RequestGroupMemberPanel({ item }: { item: RequestGroupItem }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const analysis = item.ai_analysis;
  const aiFailed = analysis?.failed ?? false;
  const bodyId = `group-step-${item.sequence_order}-body`;

  return (
    <Card size="small" styles={{ body: { padding: 0 } }} data-testid={`group-step-${item.sequence_order}`}>
      <button
        type="button"
        data-testid={`group-step-${item.sequence_order}-toggle`}
        aria-expanded={expanded}
        aria-controls={bodyId}
        aria-label={t(expanded ? 'requestGroups.detail.collapseStep' : 'requestGroups.detail.expandStep')}
        onClick={() => setExpanded((v) => !v)}
        className="u-flex u-items-center u-wrap u-w-full u-p-3 u-pointer" style={{ gap: 10, background: 'none', border: 'none', textAlign: 'left', font: 'inherit', color: 'inherit' }}
      >
        <span className="mono muted">#{item.sequence_order + 1}</span>
        <Tag>{targetKindLabel(t, item.target_kind)}</Tag>
        <RequestGroupItemStatusPill status={item.status} size="sm" />
        {item.ai_risk_level != null && (
          <RiskPill level={item.ai_risk_level} score={item.ai_risk_score} size="sm" failed={aiFailed} />
        )}
        <span className="mono muted u-flex-1 u-min-w-0 u-nowrap u-overflow-hidden u-ellipsis">
          {item.target_kind === 'QUERY' ? item.sql_text : `${item.verb} ${item.request_path}`}
        </span>
        {item.duration_ms != null && (
          <span className="muted u-text-xs">
            {item.duration_ms} ms
          </span>
        )}
        <DownOutlined
          className="muted u-text-2xs"
          style={{ transform: expanded ? 'rotate(180deg)' : undefined, transition: 'transform 0.15s' }}
        />
      </button>

      {expanded && (
        <div
          id={bodyId}
          data-testid={bodyId}
          className="u-flex u-col u-gap-3" style={{ padding: '0 12px 12px' }}
        >
          <DetailCard title={t('requestGroups.detail.stepRequest')}>
            <div style={{ padding: 14 }}>
              {item.target_kind === 'QUERY' ? (
                <>
                  <div className="muted u-text-xs u-mb-2">
                    {item.datasource_name ?? item.datasource_id}
                  </div>
                  {item.sql_text && <SqlBlock sql={item.sql_text} />}
                </>
              ) : (
                <div className="mono u-text-xs">
                  <span className="u-fw-600">{item.verb}</span> {item.request_path}
                  {item.api_connector_name && (
                    <span className="muted"> · {item.api_connector_name}</span>
                  )}
                </div>
              )}
            </div>
          </DetailCard>

          <div data-testid={`group-step-${item.sequence_order}-ai`}>
            <DetailCard
              title={
                aiFailed
                  ? t('queries.detail.ai_failed_accordion_title')
                  : t('queries.detail.card_ai')
              }
              icon={<ThunderboltOutlined className="u-fg-accent" />}
              extra={
                analysis ? (
                  <>
                    <RiskPill level={analysis.risk_level} score={analysis.risk_score} failed={aiFailed} />
                    {!aiFailed && (
                      <span className="mono muted u-ml-auto u-text-2xs">
                        {analysis.ai_provider.toLowerCase()} · {analysis.ai_model}
                      </span>
                    )}
                  </>
                ) : null
              }
            >
              {!analysis ? (
                <div className="u-text-sm" style={{ padding: 14 }}>
                  <span className="muted">{t('requestGroups.detail.aiNotAvailable')}</span>
                </div>
              ) : aiFailed ? (
                <div className="u-text-sm" style={{ padding: 14, lineHeight: 1.55 }}>
                  <div className="u-mb-3">
                    {t('queries.detail.ai_failed_accordion_body')}
                  </div>
                  <div
                    className="u-mono u-text-xs u-bg-sunken u-border u-rounded-sm u-py-2 u-px-3"
                  >
                    <span className="muted u-mr-2">
                      {t('queries.detail.ai_failed_reason_label')}:
                    </span>
                    {analysis.error_message || '—'}
                  </div>
                </div>
              ) : (
                <>
                  <div className="u-text-sm" style={{ padding: 14, lineHeight: 1.55 }}>
                    {analysis.summary}
                  </div>
                  {analysis.issues.length > 0 && (
                    <div
                      className="u-flex u-col u-gap-2" style={{ padding: '0 14px 14px' }}
                    >
                      {analysis.issues.map((iss, i) => (
                        <IssueCard key={i} issue={iss} />
                      ))}
                    </div>
                  )}
                  {analysis.optimizations.length > 0 && (
                    <div
                      className="u-flex u-col u-gap-2" style={{ padding: '0 14px 14px' }}
                    >
                      <div
                        className="muted u-text-2xs u-fw-500 u-uppercase u-tracking-wide"
                      >
                        {t('queries.detail.optimizations_title')}
                      </div>
                      {analysis.optimizations.map((opt, i) => (
                        <OptimizationCard key={i} optimization={opt} />
                      ))}
                    </div>
                  )}
                </>
              )}
            </DetailCard>
          </div>

          {item.error_message && (
            <DetailCard title={t('requestGroups.detail.error')}>
              <div className="u-text-xs u-fg-risk-high" style={{ padding: 14 }}>
                {item.error_message}
              </div>
            </DetailCard>
          )}
        </div>
      )}
    </Card>
  );
}
