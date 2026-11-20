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
      <div className="u-flex u-items-center u-gap-2 u-mb-3">
        <ApartmentOutlined className="u-fg-muted" />
        <div>
          <div className="u-fw-600 u-text-xs">Review plan: {plan.name}</div>
          <div className="muted u-text-2xs">{plan.description ?? ''}</div>
        </div>
        <div className="u-flex-1" />
        <span className="mono muted u-text-2xs">
          timeout · {plan.approval_timeout_hours}h
        </span>
      </div>
      <div className="u-flex u-items-center u-gap-0 u-wrap">
        {stages.map((s, i) => (
          <Fragment key={i}>
            <div
              className="u-flex-1 u-bg-sunken u-rounded u-border u-py-2 u-px-2" style={{ minWidth: 120 }}
            >
              <div className="u-text-2xs u-fw-600 u-mb-1">{s.label}</div>
              <div className="mono muted u-text-2xs">{s.detail}</div>
            </div>
            {i < stages.length - 1 && (
              <RightOutlined className="u-fg-faint u-text-2xs" style={{ margin: '0 6px' }} />
            )}
          </Fragment>
        ))}
      </div>
    </div>
  );
}
