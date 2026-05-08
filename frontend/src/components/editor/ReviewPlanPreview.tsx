import { Fragment } from 'react';
import { ApartmentOutlined, RightOutlined } from '@ant-design/icons';
import { Skeleton } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { Datasource, ReviewPlan } from '@/types/api';
import { listReviewPlans, reviewPlanKeys } from '@/api/reviewPlans';
import { EmptyState } from '@/components/common/EmptyState';

interface Props {
  ds: Datasource;
}

const CARD_STYLE = {
  background: 'var(--bg-elev)',
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-md)',
  padding: 14,
} as const;

export function ReviewPlanPreview({ ds }: Props) {
  const { t } = useTranslation();
  const plansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: listReviewPlans,
  });

  if (plansQuery.isLoading) {
    return (
      <div style={CARD_STYLE}>
        <Skeleton active paragraph={{ rows: 2 }} />
      </div>
    );
  }

  const plan: ReviewPlan | null =
    plansQuery.data?.find((p) => p.id === ds.review_plan_id) ?? null;

  if (!plan) {
    return (
      <div style={CARD_STYLE}>
        <EmptyState
          title={t('editor.review_plan_preview.empty_title')}
          description={t('editor.review_plan_preview.empty_description')}
          icon={<ApartmentOutlined />}
        />
      </div>
    );
  }

  const stages: { label: string; detail: string }[] = [];
  if (plan.requires_ai_review) stages.push({ label: 'AI review', detail: 'anthropic · claude-sonnet-4' });
  if (plan.requires_human_approval) {
    for (let i = 0; i < plan.min_approvals_required; i++) {
      stages.push({
        label: `Human approval${plan.min_approvals_required > 1 ? ` · stage ${i + 1}` : ''}`,
        detail: i === 0 ? 'reviewer or admin' : 'admin only',
      });
    }
  }
  stages.push({ label: 'Execute', detail: 'proxy → customer DB' });

  return (
    <div style={CARD_STYLE}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
        <ApartmentOutlined style={{ color: 'var(--fg-muted)' }} />
        <div>
          <div style={{ fontWeight: 600, fontSize: 12 }}>Review plan: {plan.name}</div>
          <div className="muted" style={{ fontSize: 11 }}>{plan.description ?? ''}</div>
        </div>
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 10 }}>
          timeout · {plan.approval_timeout_hours}h
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
