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
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          flexWrap: 'wrap',
          width: '100%',
          padding: 12,
          background: 'none',
          border: 'none',
          cursor: 'pointer',
          textAlign: 'left',
          font: 'inherit',
          color: 'inherit',
        }}
      >
        <span className="mono muted">#{item.sequence_order + 1}</span>
        <Tag>{targetKindLabel(t, item.target_kind)}</Tag>
        <RequestGroupItemStatusPill status={item.status} size="sm" />
        {item.ai_risk_level != null && (
          <RiskPill level={item.ai_risk_level} score={item.ai_risk_score} size="sm" failed={aiFailed} />
        )}
        <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} className="mono muted">
          {item.target_kind === 'QUERY' ? item.sql_text : `${item.verb} ${item.request_path}`}
        </span>
        {item.duration_ms != null && (
          <span className="muted" style={{ fontSize: 12 }}>
            {item.duration_ms} ms
          </span>
        )}
        <DownOutlined
          className="muted"
          style={{
            fontSize: 11,
            transform: expanded ? 'rotate(180deg)' : undefined,
            transition: 'transform 0.15s',
          }}
        />
      </button>

      {expanded && (
        <div
          id={bodyId}
          data-testid={bodyId}
          style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: '0 12px 12px' }}
        >
          <DetailCard title={t('requestGroups.detail.stepRequest')}>
            <div style={{ padding: 14 }}>
              {item.target_kind === 'QUERY' ? (
                <>
                  <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
                    {item.datasource_name ?? item.datasource_id}
                  </div>
                  {item.sql_text && <SqlBlock sql={item.sql_text} />}
                </>
              ) : (
                <div className="mono" style={{ fontSize: 12 }}>
                  <span style={{ fontWeight: 600 }}>{item.verb}</span> {item.request_path}
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
              icon={<ThunderboltOutlined style={{ color: 'var(--accent)' }} />}
              extra={
                analysis ? (
                  <>
                    <RiskPill level={analysis.risk_level} score={analysis.risk_score} failed={aiFailed} />
                    {!aiFailed && (
                      <span className="mono muted" style={{ marginLeft: 'auto', fontSize: 11 }}>
                        {analysis.ai_provider.toLowerCase()} · {analysis.ai_model}
                      </span>
                    )}
                  </>
                ) : null
              }
            >
              {!analysis ? (
                <div style={{ padding: 14, fontSize: 13 }}>
                  <span className="muted">{t('requestGroups.detail.aiNotAvailable')}</span>
                </div>
              ) : aiFailed ? (
                <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
                  <div style={{ marginBottom: 12 }}>
                    {t('queries.detail.ai_failed_accordion_body')}
                  </div>
                  <div
                    style={{
                      background: 'var(--bg-sunken)',
                      border: '1px solid var(--border)',
                      borderRadius: 'var(--radius-sm)',
                      padding: '8px 12px',
                      fontFamily: 'var(--font-mono)',
                      fontSize: 12,
                    }}
                  >
                    <span className="muted" style={{ marginRight: 8 }}>
                      {t('queries.detail.ai_failed_reason_label')}:
                    </span>
                    {analysis.error_message || '—'}
                  </div>
                </div>
              ) : (
                <>
                  <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
                    {analysis.summary}
                  </div>
                  {analysis.issues.length > 0 && (
                    <div
                      style={{
                        padding: '0 14px 14px',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 8,
                      }}
                    >
                      {analysis.issues.map((iss, i) => (
                        <IssueCard key={i} issue={iss} />
                      ))}
                    </div>
                  )}
                  {analysis.optimizations.length > 0 && (
                    <div
                      style={{
                        padding: '0 14px 14px',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 8,
                      }}
                    >
                      <div
                        className="muted"
                        style={{
                          fontSize: 11,
                          textTransform: 'uppercase',
                          letterSpacing: '0.04em',
                          fontWeight: 500,
                        }}
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
              <div style={{ padding: 14, color: 'var(--risk-high)', fontSize: 12 }}>
                {item.error_message}
              </div>
            </DetailCard>
          )}
        </div>
      )}
    </Card>
  );
}
