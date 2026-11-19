import { useTranslation } from 'react-i18next';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { RiskPill } from '@/components/common/RiskPill';
import { SqlBlock } from '@/components/common/SqlBlock';
import { ApprovalPredictionBadge } from '@/components/review/ApprovalPredictionBadge';
import { timeAgo } from '@/utils/dateFormat';
import type { PendingReviewItem } from '@/types/api';
import { ReviewPreview } from './ReviewPreview';

interface QueryReviewPreviewProps {
  item: PendingReviewItem | null;
  onApprove: (id: string) => void;
  onReject: (id: string) => void;
}

/** The query queue's side panel, built from the queue payload — no extra request. */
export function QueryReviewPreview({ item, onApprove, onReject }: QueryReviewPreviewProps) {
  const { t } = useTranslation();
  if (!item) return <ReviewPreview content={null} onApprove={onApprove} onReject={onReject} />;
  const ai = item.ai_analysis;
  const blocking = item.sql_review_blocking_count ?? 0;
  return (
    <ReviewPreview
      onApprove={onApprove}
      onReject={onReject}
      content={{
        id: item.id,
        heading: item.id.slice(0, 8),
        badges: (
          <>
            <QueryTypePill type={item.query_type} size="sm" />
            {ai && <RiskPill level={ai.risk_level} score={ai.risk_score} size="sm" />}
            {item.approval_probability != null && (
              <ApprovalPredictionBadge probability={item.approval_probability} size="sm" />
            )}
          </>
        ),
        meta: [
          { label: t('reviews.col_submitter'), value: item.submitted_by.email },
          { label: t('reviews.col_datasource'), value: <span className="mono">{item.datasource.name}</span> },
          { label: t('reviews.col_created'), value: timeAgo(item.created_at) },
          ...(blocking > 0
            ? [{
                label: t('reviews.preview.sql_review'),
                value: t('reviews.sql_review_blocking_tooltip', { count: blocking }),
              }]
            : []),
        ],
        sections: [
          { label: t('reviews.preview.sql'), content: <SqlBlock sql={item.sql_text} /> },
          { label: t('reviews.preview.justification'), content: item.justification },
          { label: t('reviews.preview.ai_summary'), content: ai?.summary ?? '' },
        ],
        detailsHref: `/queries/${item.id}`,
      }}
    />
  );
}
