import { Fragment } from 'react';
import { ApartmentOutlined, RightOutlined } from '@ant-design/icons';
import type { AiAnalysis, Datasource } from '@/types/api';
import { REVIEW_PLANS } from '@/mocks/data';

interface Props {
  ds: Datasource;
  analysis: AiAnalysis | null;
}

export function ReviewPlanPreview({ ds, analysis }: Props) {
  const plan = REVIEW_PLANS.find((p) => p.id === ds.plan)!;
  const willSkipHuman = plan.id === 'rp-light' && analysis?.risk_level === 'LOW';
  const stages: { label: string; detail: string }[] = [];
  if (plan.requires_ai) stages.push({ label: 'AI review', detail: 'anthropic · claude-sonnet-4' });
  if (plan.requires_human && !willSkipHuman) {
    for (let i = 0; i < plan.min_approvals; i++) {
      stages.push({
        label: `Human approval${plan.min_approvals > 1 ? ` · stage ${i + 1}` : ''}`,
        detail: i === 0 ? 'reviewer or admin' : 'admin only',
      });
    }
  }
  stages.push({ label: 'Execute', detail: 'proxy → customer DB' });

  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 14,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
        <ApartmentOutlined style={{ color: 'var(--fg-muted)' }} />
        <div>
          <div style={{ fontWeight: 600, fontSize: 12 }}>Review plan: {plan.name}</div>
          <div className="muted" style={{ fontSize: 11 }}>{plan.description}</div>
        </div>
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 10 }}>
          timeout · {plan.timeout_hours}h
        </span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 0, flexWrap: 'wrap' }}>
        {stages.map((s, i) => (
          <Fragment key={i}>
            <div
              style={{
                flex: 1,
                minWidth: 120,
                padding: '8px 10px',
                background: 'var(--bg-sunken)',
                borderRadius: 6,
                border: '1px solid var(--border)',
              }}
            >
              <div style={{ fontSize: 11, fontWeight: 600, marginBottom: 2 }}>{s.label}</div>
              <div className="mono muted" style={{ fontSize: 10 }}>{s.detail}</div>
            </div>
            {i < stages.length - 1 && (
              <RightOutlined style={{ margin: '0 6px', color: 'var(--fg-faint)', fontSize: 11 }} />
            )}
          </Fragment>
        ))}
      </div>
    </div>
  );
}
